#include <Arduino.h>
#include <math.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <driver/pulse_cnt.h>
#include <driver/gpio.h>

#define DEVICE_NAME     "ESP32S3-Angulos"
#define SERVICE_UUID    "7a1d0000-5e2f-4b8a-9c3d-2f1e0a6b4c11"
#define ANGLE_CHAR_UUID "7a1d0001-5e2f-4b8a-9c3d-2f1e0a6b4c11"
// Servidor GATT de estado: lectura + notificación con la posición medida.
#define STATE_CHAR_UUID "7a1d0002-5e2f-4b8a-9c3d-2f1e0a6b4c11"

#define EN_L  14
#define EN_R  12
#define PWM_L 4
#define PWM_R 5
#define ENC_A 21
#define ENC_B 47

// Protocolo BLE, igual en lib/ble/angle_protocol.dart.

// Ángulo (característica de ángulo, 4 bytes):
//   [0] 0xA5  [1] canal  [2..3] ángulo int16 little-endian en décimas de grado
// Modo de rango de la perilla (0–180, 0–360, ±90...), misma característica:
//   [0] 0xA7  [1] modo
//   La ESP no lo usa para controlar; solo lo guarda y lo publica para que
//   todos los dispositivos conectados muestren el mismo.
// Estado (característica de estado, 15 bytes, little-endian):
//   [0] 0xA6  [1] canal  [2..5] posición int32 en décimas de grado
//   [6..9] referencia int32 en décimas  [10..11] rpm int16 en décimas
//   [12..13] PWM int16 (-255..255)  [14] modo (0xFF = sin definir)
constexpr uint8_t FRAME_HEADER  = 0xA5;
constexpr size_t  FRAME_LENGTH  = 4;
constexpr uint8_t MODE_HEADER   = 0xA7;
constexpr uint8_t STATE_HEADER  = 0xA6;
constexpr size_t  STATE_LENGTH  = 15;
constexpr uint8_t MOTOR_CHANNEL = 0;

static volatile float pos_ref = 0;
static volatile uint8_t rangeMode = 0xFF;
static volatile bool needAdvertising = false;

static BLEServer *bleServer = nullptr;
static BLECharacteristic *stateChar = nullptr;

const float ENC_PPR    = 11.0;
const float GEAR_RATIO = 34.0;
// Cuadratura x4 (flancos de A y B): 1496 cuentas por vuelta, 0.24° cada una.
const float CPR        = ENC_PPR * GEAR_RATIO * 4;

const unsigned long SAMPLE_MS = 10;    // periodo del control
const unsigned long STATE_MS  = 100;   // periodo de publicación del estado

// PID por asignación de polos sobre el motor identificado en lazo abierto
// (rampa y escalones de PWM, registro a 10 ms, encoder x4):
//   velocidad = K (u - u0),  K = 4.26 °/s por cuenta,  tau ~ 25 ms
//   zona muerta en movimiento u0 ~ 16 (+) / 22 (-); arranque ~ 36 / 40
//   G(s) = K / (s (tau s + 1))
// Lazo cerrado: tau s^3 + (1 + K kd) s^2 + K kp s + K ki = 0, con polos en
// wn = 18 rad/s, zeta = 1 y un tercero en 1.5 wn:
//   kp = 7.6   ki = 51   kd = 0.135
// Medido en el motor (+45 -> -45 -> +10 -> 0): 0 % de sobrepaso, asentamiento
// en ±1.5° en <= 0.25 s, error final <= 0.5°, sin titubeo; seno de 67° a
// 0.69 Hz con 1.8° de error de forma y ~50 ms de retardo.
const float kp = 7.6;
const float ki = 51.0;
const float kd = 0.135;

// Fricción: el motor no arranca con PWM bajo y el integrador, al acumular
// para vencerla, hace que el eje titubee (stick-slip).
//   DEADBAND: dentro de ±0.5° el error cuenta como cero; no se integra y
//             solo queda el freno de D.
//   U_FRIC:   empuje en la dirección del error para cruzar la zona muerta
//             sin esperar al integrador.
//   I_ZONE:   solo se integra con |e| < 5°: el recorrido lo hacen P y U_FRIC
//             y el integrador queda para los últimos grados. Si integrara
//             durante todo el movimiento llegaría cargado.
const float DEADBAND = 0.5;    // grados (~2 cuentas del encoder)
const float U_FRIC   = 12;     // cuentas de PWM
const float I_ZONE   = 5;      // grados

static float e_integral = 0;
static float last_pos = 0;
static float d_filt = 0;       // velocidad filtrada (°/s) para el término D

static unsigned long lastSampleTime = 0;
static unsigned long lastStateTime = 0;
static long lastStateCount = 0;

// Encoder

// Leído por el contador de pulsos (PCNT) del S3, sin interrupciones. En
// cuadratura completa un rebote en una fase suma +1 y luego -1, así que se
// cancela solo; además el filtro de glitches ignora pulsos < 2 us (el ruido
// del PWM).
static pcnt_unit_handle_t encUnit = nullptr;

static long readEncoder() {
  int c = 0;
  pcnt_unit_get_count(encUnit, &c);
  return c;
}

static void setupEncoder() {
  pcnt_unit_config_t unit = {};
  unit.low_limit = -30000;
  unit.high_limit = 30000;
  unit.flags.accum_count = 1;          // sigue contando al pasar los límites
  ESP_ERROR_CHECK(pcnt_new_unit(&unit, &encUnit));

  pcnt_glitch_filter_config_t filter = {};
  filter.max_glitch_ns = 2000;
  ESP_ERROR_CHECK(pcnt_unit_set_glitch_filter(encUnit, &filter));

  pcnt_chan_config_t a = {};
  a.edge_gpio_num = ENC_A;
  a.level_gpio_num = ENC_B;
  pcnt_channel_handle_t chA;
  ESP_ERROR_CHECK(pcnt_new_channel(encUnit, &a, &chA));
  pcnt_chan_config_t b = {};
  b.edge_gpio_num = ENC_B;
  b.level_gpio_num = ENC_A;
  pcnt_channel_handle_t chB;

  

  ESP_ERROR_CHECK(pcnt_new_channel(encUnit, &b, &chB));

  // A sube con B en alto -> resta.
  pcnt_channel_set_edge_action(chA, PCNT_CHANNEL_EDGE_ACTION_DECREASE, PCNT_CHANNEL_EDGE_ACTION_INCREASE);
  pcnt_channel_set_level_action(chA, PCNT_CHANNEL_LEVEL_ACTION_KEEP, PCNT_CHANNEL_LEVEL_ACTION_INVERSE);
  pcnt_channel_set_edge_action(chB, PCNT_CHANNEL_EDGE_ACTION_INCREASE, PCNT_CHANNEL_EDGE_ACTION_DECREASE);
  pcnt_channel_set_level_action(chB, PCNT_CHANNEL_LEVEL_ACTION_KEEP, PCNT_CHANNEL_LEVEL_ACTION_INVERSE);

  // Con accum_count hacen falta los puntos de vigilancia en los límites.
  pcnt_unit_add_watch_point(encUnit, unit.low_limit);
  pcnt_unit_add_watch_point(encUnit, unit.high_limit);

  // El driver configura los pines como entrada; se reafirman los pull-up.
  gpio_pullup_en((gpio_num_t)ENC_A);
  gpio_pullup_en((gpio_num_t)ENC_B);

  ESP_ERROR_CHECK(pcnt_unit_enable(encUnit));
  ESP_ERROR_CHECK(pcnt_unit_clear_count(encUnit));
  ESP_ERROR_CHECK(pcnt_unit_start(encUnit));
}

// BLE

static void put32(uint8_t *p, int32_t v) {
  for (int i = 0; i < 4; i++) p[i] = (uint8_t)(v >> (8 * i));
}

static void put16(uint8_t *p, int16_t v) {
  p[0] = (uint8_t)v;
  p[1] = (uint8_t)(v >> 8);
}

// Publica el estado: queda para lecturas y se notifica a los suscritos.
static void publishState(float pos, float ref, float rpm, float u) {
  uint8_t f[STATE_LENGTH];
  f[0] = STATE_HEADER;
  f[1] = MOTOR_CHANNEL;
  put32(f + 2, (int32_t)lroundf(pos * 10));
  put32(f + 6, (int32_t)lroundf(ref * 10));
  put16(f + 10, (int16_t)constrain(lroundf(rpm * 10), -32768L, 32767L));
  put16(f + 12, (int16_t)lroundf(u));
  f[14] = rangeMode;
  stateChar->setValue(f, STATE_LENGTH);
  if (bleServer->getConnectedCount() > 0) stateChar->notify();
}

class AngleCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    const uint8_t *data = characteristic->getData();
    const size_t   len  = characteristic->getLength();

    if (len == 2 && data[0] == MODE_HEADER) {
      rangeMode = data[1];
    } else if (len == FRAME_LENGTH && data[0] == FRAME_HEADER && data[1] == MOTOR_CHANNEL) {
      const int16_t tenths = (int16_t)((uint16_t)data[2] | ((uint16_t)data[3] << 8));
      pos_ref = tenths / 10.0f;   // set point
    }
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  // NimBLE deja de anunciar al conectarse un cliente. Se reanuda (en loop(),
  // fuera de la tarea BLE) para que otro dispositivo pueda conectarse a la
  // vez o volver a encontrarla tras desconectarse; el máximo es 3.
  void onConnect(BLEServer *) override { needAdvertising = true; }
  void onDisconnect(BLEServer *) override { needAdvertising = true; }
};

static void setupBle() {
  BLEDevice::init(DEVICE_NAME);

  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  BLEService *service = bleServer->createService(SERVICE_UUID);
  BLECharacteristic *angleChar = service->createCharacteristic(
      ANGLE_CHAR_UUID, BLECharacteristic::PROPERTY_WRITE_NR);
  angleChar->setCallbacks(new AngleCallbacks());
  // NimBLE agrega solo el descriptor 0x2902 por tener NOTIFY.
  stateChar = service->createCharacteristic(
      STATE_CHAR_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();
}

// Motor

static void drive(float u) {
  if (u > 0) {
    analogWrite(PWM_R, u);
    analogWrite(PWM_L, 0);
  } else {
    analogWrite(PWM_R, 0);
    analogWrite(PWM_L, -u);
  }
}

void setup() {
  pinMode(EN_R, OUTPUT);
  pinMode(EN_L, OUTPUT);
  pinMode(PWM_L, OUTPUT);
  pinMode(PWM_R, OUTPUT);

  digitalWrite(EN_R, 1);
  digitalWrite(EN_L, 1);

  analogWriteFrequency(PWM_L, 20000);
  analogWriteFrequency(PWM_R, 20000);
  drive(0);

  setupEncoder();
  setupBle();

  lastSampleTime = lastStateTime = millis();
}

void loop() {
  if (needAdvertising) {
    needAdvertising = false;
    if (bleServer->getConnectedCount() < CONFIG_BT_NIMBLE_MAX_CONNECTIONS) {
      BLEDevice::startAdvertising();
    }
  }

  const unsigned long now = millis();
  if (now - lastSampleTime < SAMPLE_MS) {
    delay(1);
    return;
  }

  const long count = readEncoder();
  const float dt = (now - lastSampleTime) / 1000.0;
  lastSampleTime = now;

  const float ref = pos_ref;
  const float pos = count / CPR * 360;
  const float e = ref - pos;

  d_filt = 0.8 * d_filt + 0.2 * (pos - last_pos) / dt;
  last_pos = pos;

  float u;
  if (fabsf(e) <= DEADBAND) {
    u = -(kd * d_filt);
  } else {
    u = (kp * e) + (ki * e_integral) - (kd * d_filt) + (e > 0 ? U_FRIC : -U_FRIC);
    const bool saturado = (u >= 255 && e > 0) || (u <= -255 && e < 0);
    if (fabsf(e) >= I_ZONE) e_integral = 0;
    else if (!saturado) e_integral += e * dt;
  }

  u = constrain(u, -255, 255);
  drive(u);

  if (now - lastStateTime >= STATE_MS) {
    const float rpm = (count - lastStateCount) / CPR / ((now - lastStateTime) / 60000.0);
    publishState(pos, ref, rpm, u);
    lastStateCount = count;
    lastStateTime = now;
  }
}
