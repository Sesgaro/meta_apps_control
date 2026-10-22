// ESP32-S3 del gato: árbitro del juego, eje Y de la máquina cartesiana y
// puente BLE con los dos Meta Quest.
//
//   Quest 1 ─┐ BLE                    UART
//   Quest 2 ─┴──► ESP32-S3 ◄─────────────────► ESP32 normal (esp32_eje_x)
//
//   ESP32-S3 (este código)          ESP32 normal
//   · reglas (gato.h)               · motor eje X (cremallera)
//   · motor eje Y (husillo T8)
//   · matriz LDR 3×3 (fija)
//
// Cada eje se controla en milímetros: el PID trabaja en grados del motor (las
// ganancias ajustadas valen igual) y la frontera convierte con el avance por
// vuelta de cada eje.
#include <Arduino.h>
#include <math.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <driver/pulse_cnt.h>
#include <driver/gpio.h>

#include "gato.h"

// SIMULAR=1: prueba sin máquina. La S3 se inventa el movimiento de los dos
// ejes y lo que ve la matriz IR (como "Probar sin ESP" en la app), pero el
// árbitro, el BLE y el reenvío de poses son los de verdad: sirve para probar
// dos visores sin motores ni la ESP32 normal. Se compila con
//   arduino-cli compile --build-property "compiler.cpp.extra_flags=-DSIMULAR=1" ...
#ifndef SIMULAR
#define SIMULAR 0
#endif

#define DEVICE_NAME       "ESP32S3-Gato"
#define SERVICE_UUID      "7a1d1000-5e2f-4b8a-9c3d-2f1e0a6b4c11"
#define COMMAND_CHAR_UUID "7a1d1001-5e2f-4b8a-9c3d-2f1e0a6b4c11"
#define STATE_CHAR_UUID   "7a1d1002-5e2f-4b8a-9c3d-2f1e0a6b4c11"
#define POSE_CHAR_UUID    "7a1d1003-5e2f-4b8a-9c3d-2f1e0a6b4c11"

// Motor del eje Y
#define EN_L  14
#define EN_R  12
#define PWM_L 4
#define PWM_R 5
#define ENC_A 21
#define ENC_B 47

// Matriz de LDR (módulo con salida AO), una por casilla del gato.
// Índice = fila * 3 + columna; fila 0 = Y mínima, columna 0 = X mínima.
// ADC1 (1-10) salvo la casilla 8: no quedan más pines ADC1 libres.
const int LDR_PINES[9] = {1, 2, 3, 6, 7, 8, 9, 10, 11};
// Lectura de 0 a 4095. El módulo da más voltaje con menos luz: con la cabeza
// encima (sombra) la lectura sube del umbral. Si tu cabeza lleva luz en vez
// de tapar, pon LDR_SOMBRA en false. Ajusta el umbral con el monitor serie.
// shortcut: un solo umbral para las 9; si varían mucho, uno por casilla.
const int LDR_UMBRAL = 2000;
const bool LDR_SOMBRA = true;

// UART con la ESP32 normal (Serial1). Cruzado: TX -> RX, RX <- TX, GND común.
#define UART_TX 17
#define UART_RX 18
const unsigned long UART_BAUD = 115200;

// --- Geometría (calibrar en la máquina) ------------------------------------
// Eje Y: husillo (el eje Z de una BIQU B1: T8) = 8 mm por vuelta del eje de
// salida del motor (paso × entradas). Si hay otra reducción, inclúyela.
const float MM_POR_VUELTA_Y = 8.0;
// Recorrido útil de cada eje. La posición al encender es 0 mm (encoders
// incrementales): enciende con la cabeza en la esquina mínima (X e Y).
const float X_MIN = 0, X_MAX = 180;
const float Y_MIN = 0, Y_MAX = 180;
// Centro de la primera casilla y distancia entre casillas, por eje.
const float X_CASILLA0 = 30, X_PASO = 60;
const float Y_CASILLA0 = 30, Y_PASO = 60;
// Ambos ejes a menos de esto de su referencia = la cabeza llegó.
const float TOLERANCIA_MM = 2.0;

// --- Juego ---------------------------------------------------------------------
gato::Juego juego([] {
  gato::Config c;
  c.turnoMs = 20000;       // el husillo del eje Y avanza ~17 mm/s
  c.quietoMs = 300;
  c.resolverMaxMs = 12000;  // tras tirar, Y puede recorrer los 180 mm
  return c;
}());

// --- Protocolo BLE (igual en lib/ble/game_protocol.dart) -------------------
// Comandos, característica de comandos (escritura sin respuesta):
//   C0 jugador xLo xHi yLo yHi   mover la cabeza; int16 en décimas de mm.
//                                Solo cuenta si es el turno de ese jugador.
//   C1 01                        partida nueva
//   C2 jugador                   tirar: termina el turno ya y se lee el IR
// Estado, característica de estado (lectura + notificación cada 100 ms):
//   [0] C6  [1] fase  [2] turno  [3..4] tiempo restante (décimas de s)
//   [5..13] tablero (0 vacía, 1 X, 2 O)  [14] ganador (3 = empate)
//   [15] último resultado  [16] última casilla  [17] último jugador
//   [18..25] posX posY refX refY (int16, décimas de mm)
//   [26] casilla que ve la matriz IR (FF ninguna, FE varias)
//   [27] banderas: bit0 = enlace UART con la ESP32 normal
//   [28..35] xMin xMax yMin yMax  [36..43] xCasilla0 xPaso yCasilla0 yPaso
//   [44] duración del turno (s)
// Poses, característica de poses (escritura sin respuesta + notificación):
//   D0 jugador ... (30 bytes) cabeza, manos y pelota de un visor en mm del
//   tablero (ver lib/ble/pose_frame.dart). La ESP no las interpreta: guarda
//   la última de cada jugador y la reenvía a todos, así cada visor ve al otro.
constexpr uint8_t CMD_MOVER = 0xC0;
constexpr uint8_t CMD_JUEGO = 0xC1;
constexpr uint8_t CMD_TIRAR = 0xC2;
constexpr uint8_t STATE_HEADER = 0xC6;
constexpr size_t STATE_LENGTH = 45;
constexpr uint8_t POSE_HEADER = 0xD0;
constexpr size_t POSE_LENGTH = 30;
constexpr unsigned long POSE_MS = 50;  // reenvío: 20 Hz como máximo

static BLEServer *bleServer = nullptr;
static BLECharacteristic *stateChar = nullptr;
static BLECharacteristic *poseChar = nullptr;
static volatile bool needAdvertising = false;

// Lo escribe la tarea BLE y lo lee loop().
static portMUX_TYPE bloqueo = portMUX_INITIALIZER_UNLOCKED;
static float pedidoX = 0, pedidoY = 0;
static uint8_t pedidoJugador = 0;
static bool hayPedido = false;
static bool pedirPartida = false;
static uint8_t tiroJugador = 0;   // 0 = nadie tiró
// Última pose de cada jugador (índice = jugador - 1) y si falta reenviarla.
static uint8_t poses[2][POSE_LENGTH];
static bool poseNueva[2] = {false, false};

// Referencias vigentes de la cabeza, en mm.
static float refX = 0, refY = 0;

// --- Control del eje Y (PID ajustado en el motor) ----------------------------
const float ENC_PPR    = 11.0;
const float GEAR_RATIO = 34.0;
const float CPR        = ENC_PPR * GEAR_RATIO * 4;   // cuadratura x4

const unsigned long SAMPLE_MS = 10;
const unsigned long STATE_MS  = 100;
const unsigned long UART_MS   = 50;
const unsigned long UART_TIMEOUT_MS = 500;

// Asignación de polos sobre el motor identificado (cálculo completo en
// App/firmware/esp32s3_receptor_angulos): kp 7.6, ki 51, kd 0.135.
const float kp = 7.6;
const float ki = 51.0;
const float kd = 0.135;
const float DEADBAND = 0.5;    // grados del motor
const float U_FRIC   = 12;
const float I_ZONE   = 5;

static float e_integral = 0;
static float last_pos = 0;
static float d_filt = 0;
static float posY = 0, pwmY = 0;

static pcnt_unit_handle_t encUnit = nullptr;

static float gradosDeMm(float mm) { return mm * 360.0f / MM_POR_VUELTA_Y; }
static float mmDeGrados(float g) { return g * MM_POR_VUELTA_Y / 360.0f; }

static long readEncoder() {
  int c = 0;
  pcnt_unit_get_count(encUnit, &c);
  return c;
}

static void setupEncoder() {
  pcnt_unit_config_t unit = {};
  unit.low_limit = -30000;
  unit.high_limit = 30000;
  unit.flags.accum_count = 1;
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

  pcnt_channel_set_edge_action(chA, PCNT_CHANNEL_EDGE_ACTION_DECREASE, PCNT_CHANNEL_EDGE_ACTION_INCREASE);
  pcnt_channel_set_level_action(chA, PCNT_CHANNEL_LEVEL_ACTION_KEEP, PCNT_CHANNEL_LEVEL_ACTION_INVERSE);
  pcnt_channel_set_edge_action(chB, PCNT_CHANNEL_EDGE_ACTION_INCREASE, PCNT_CHANNEL_EDGE_ACTION_DECREASE);
  pcnt_channel_set_level_action(chB, PCNT_CHANNEL_LEVEL_ACTION_KEEP, PCNT_CHANNEL_LEVEL_ACTION_INVERSE);

  pcnt_unit_add_watch_point(encUnit, unit.low_limit);
  pcnt_unit_add_watch_point(encUnit, unit.high_limit);
  gpio_pullup_en((gpio_num_t)ENC_A);
  gpio_pullup_en((gpio_num_t)ENC_B);

  ESP_ERROR_CHECK(pcnt_unit_enable(encUnit));
  ESP_ERROR_CHECK(pcnt_unit_clear_count(encUnit));
  ESP_ERROR_CHECK(pcnt_unit_start(encUnit));
}

static void drive(float u) {
  if (u > 0) {
    analogWrite(PWM_R, u);
    analogWrite(PWM_L, 0);
  } else {
    analogWrite(PWM_R, 0);
    analogWrite(PWM_L, -u);
  }
}

static void controlY(float dt) {
  const float pos = readEncoder() / CPR * 360;   // grados del motor
  const float e = gradosDeMm(refY) - pos;

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
  posY = mmDeGrados(pos);
  pwmY = u;
}

// --- UART con la ESP32 normal (eje X) ----------------------------
// Líneas "$...*HH" con checksum XOR, igual que firmware/esp32_eje_x.
static HardwareSerial &uart = Serial1;
static float posX = 0;
static uint16_t mascaraIr = 0;
static unsigned long ultimaTelemetriaX = 0;
static bool hayTelemetriaX = false;

static uint8_t checksum(const char *s, size_t n) {
  uint8_t c = 0;
  for (size_t i = 0; i < n; i++) c ^= (uint8_t)s[i];
  return c;
}

static bool enlaceX() {
  return hayTelemetriaX && millis() - ultimaTelemetriaX <= UART_TIMEOUT_MS;
}

// "$T,<posX>,<refX>,<pwm>*HH"
static void procesarLineaX(char *linea) {
  if (linea[0] != '$') return;
  char *asterisco = strrchr(linea, '*');
  if (asterisco == nullptr || strlen(asterisco) < 3) return;
  const long esperado = strtol(asterisco + 1, nullptr, 16);
  *asterisco = '\0';
  char *cuerpo = linea + 1;
  if (checksum(cuerpo, strlen(cuerpo)) != esperado || strncmp(cuerpo, "T,", 2) != 0) return;

  float pos, ref;
  int pwm;
  if (sscanf(cuerpo + 2, "%f,%f,%d", &pos, &ref, &pwm) != 3) return;
  posX = pos;
  ultimaTelemetriaX = millis();
  hayTelemetriaX = true;
}

static void leerUart() {
  static char linea[64];
  static size_t largo = 0;
  while (uart.available()) {
    const char c = uart.read();
    if (c == '\n' || c == '\r') {
      if (largo > 0) {
        linea[largo] = '\0';
        procesarLineaX(linea);
      }
      largo = 0;
    } else if (largo < sizeof(linea) - 1) {
      linea[largo++] = c;
    } else {
      largo = 0;
    }
  }
}

// "$R,<refX mm>,<habilitado>*HH"
static void enviarReferenciaX() {
  char cuerpo[32];
  const int n = snprintf(cuerpo, sizeof(cuerpo), "R,%.1f,1", refX);
  uart.printf("$%s*%02X\n", cuerpo, checksum(cuerpo, n));
}

#if SIMULAR
// Velocidades de la app (lib/ble/game_sim.dart): cremallera en X, husillo en Y.
const float SIM_VEL_X = 150, SIM_VEL_Y = 17;  // mm/s
const float SIM_RADIO_IR = 20;                // mm del centro de la casilla

/// Mueve la cabeza hacia la referencia y arma la máscara IR, en vez del
/// encoder y la UART.
static void simularMaquina(float dt) {
  posX += constrain(refX - posX, -SIM_VEL_X * dt, SIM_VEL_X * dt);
  posY += constrain(refY - posY, -SIM_VEL_Y * dt, SIM_VEL_Y * dt);
  uint16_t m = 0;
  for (int i = 0; i < 9; i++) {
    const float cx = X_CASILLA0 + (i % 3) * X_PASO, cy = Y_CASILLA0 + (i / 3) * Y_PASO;
    if (fabsf(posX - cx) <= SIM_RADIO_IR && fabsf(posY - cy) <= SIM_RADIO_IR) m |= 1 << i;
  }
  mascaraIr = m;
  ultimaTelemetriaX = millis();
  hayTelemetriaX = true;
}
#endif

// --- BLE -----------------------------------------------------------------------
static void put16(uint8_t *p, int16_t v) {
  p[0] = (uint8_t)v;
  p[1] = (uint8_t)(v >> 8);
}

static int16_t decimas(float mm) {
  return (int16_t)constrain(lroundf(mm * 10), -32768L, 32767L);
}

static void leerLdr() {
  static unsigned long ultimoLog = 0;
  uint16_t m = 0;
  int lecturas[9];
  for (int i = 0; i < 9; i++) {
    lecturas[i] = analogRead(LDR_PINES[i]);
    if ((lecturas[i] > LDR_UMBRAL) == LDR_SOMBRA) m |= 1 << i;
  }
  mascaraIr = m;
  // Para calibrar LDR_UMBRAL: lecturas por el monitor serie cada 0.5 s.
  if (millis() - ultimoLog >= 500) {
    ultimoLog = millis();
    for (int i = 0; i < 9; i++) Serial.printf("%d%c", lecturas[i], i < 8 ? ' ' : '\n');
  }
}

static uint8_t casillaIr() {
  return gato::casillaDeMascara(mascaraIr);
}

static void publicarEstado(unsigned long ahora) {
  uint8_t f[STATE_LENGTH];
  f[0] = STATE_HEADER;
  f[1] = juego.fase;
  f[2] = juego.turno;
  put16(f + 3, (int16_t)min<uint32_t>(juego.restanteMs(ahora) / 100, 32767));
  memcpy(f + 5, juego.tablero, 9);
  f[14] = juego.ganadorPartida;
  f[15] = juego.ultimo;
  f[16] = juego.ultimaCasilla;
  f[17] = juego.ultimoJugador;
  put16(f + 18, decimas(posX));
  put16(f + 20, decimas(posY));
  put16(f + 22, decimas(refX));
  put16(f + 24, decimas(refY));
  f[26] = casillaIr();
  f[27] = enlaceX() ? 1 : 0;
  put16(f + 28, decimas(X_MIN));
  put16(f + 30, decimas(X_MAX));
  put16(f + 32, decimas(Y_MIN));
  put16(f + 34, decimas(Y_MAX));
  put16(f + 36, decimas(X_CASILLA0));
  put16(f + 38, decimas(X_PASO));
  put16(f + 40, decimas(Y_CASILLA0));
  put16(f + 42, decimas(Y_PASO));
  f[44] = juego.cfg.turnoMs / 1000;
  stateChar->setValue(f, STATE_LENGTH);
  if (bleServer->getConnectedCount() > 0) stateChar->notify();
}

class CommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    const uint8_t *d = characteristic->getData();
    const size_t len = characteristic->getLength();
    if (len == 6 && d[0] == CMD_MOVER) {
      const int16_t x = (int16_t)(d[2] | (d[3] << 8));
      const int16_t y = (int16_t)(d[4] | (d[5] << 8));
      portENTER_CRITICAL(&bloqueo);
      pedidoJugador = d[1];
      pedidoX = x / 10.0f;
      pedidoY = y / 10.0f;
      hayPedido = true;
      portEXIT_CRITICAL(&bloqueo);
    } else if (len == 2 && d[0] == CMD_TIRAR) {
      portENTER_CRITICAL(&bloqueo);
      tiroJugador = d[1];
      portEXIT_CRITICAL(&bloqueo);
    } else if (len == 2 && d[0] == CMD_JUEGO && d[1] == 0x01) {
      portENTER_CRITICAL(&bloqueo);
      pedirPartida = true;
      portEXIT_CRITICAL(&bloqueo);
    }
  }
};

class PoseCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    const uint8_t *d = characteristic->getData();
    if (characteristic->getLength() != POSE_LENGTH || d[0] != POSE_HEADER) return;
    if (d[1] != gato::JUGADOR_X && d[1] != gato::JUGADOR_O) return;
    portENTER_CRITICAL(&bloqueo);
    memcpy(poses[d[1] - 1], d, POSE_LENGTH);
    poseNueva[d[1] - 1] = true;
    portEXIT_CRITICAL(&bloqueo);
  }
};

/// Reenvía a todos las poses que llegaron desde la última vez.
static void reenviarPoses() {
  for (int i = 0; i < 2; i++) {
    uint8_t f[POSE_LENGTH];
    portENTER_CRITICAL(&bloqueo);
    const bool nueva = poseNueva[i];
    if (nueva) memcpy(f, poses[i], POSE_LENGTH);
    poseNueva[i] = false;
    portEXIT_CRITICAL(&bloqueo);
    if (!nueva || bleServer->getConnectedCount() == 0) continue;
    poseChar->setValue(f, POSE_LENGTH);
    poseChar->notify();
  }
}

class ServerCallbacks : public BLEServerCallbacks {
  // NimBLE deja de anunciar al conectarse alguien; se reanuda para que el
  // otro Quest pueda entrar (máximo 3 conexiones).
  void onConnect(BLEServer *) override { needAdvertising = true; }
  void onDisconnect(BLEServer *) override { needAdvertising = true; }
};

static void setupBle() {
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(100);   // el estado mide 45 bytes

  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  BLEService *service = bleServer->createService(SERVICE_UUID);
  BLECharacteristic *cmd = service->createCharacteristic(
      COMMAND_CHAR_UUID, BLECharacteristic::PROPERTY_WRITE_NR);
  cmd->setCallbacks(new CommandCallbacks());
  stateChar = service->createCharacteristic(
      STATE_CHAR_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  poseChar = service->createCharacteristic(
      POSE_CHAR_UUID, BLECharacteristic::PROPERTY_WRITE_NR | BLECharacteristic::PROPERTY_NOTIFY);
  poseChar->setCallbacks(new PoseCallbacks());
  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();
}

// --- Juego -----------------------------------------------------------------------

/// Aplica los comandos que llegaron por BLE. Los movimientos solo cuentan si
/// es el turno de ese jugador: así se "bloquean" los motores para el otro.
static void atenderComandos(unsigned long ahora) {
  portENTER_CRITICAL(&bloqueo);
  const bool partida = pedirPartida;
  const bool pedido = hayPedido;
  const uint8_t jugador = pedidoJugador;
  const float x = pedidoX, y = pedidoY;
  const uint8_t tiro = tiroJugador;
  pedirPartida = hayPedido = false;
  tiroJugador = 0;
  portEXIT_CRITICAL(&bloqueo);

  if (partida) juego.nueva(ahora);
  if (pedido && juego.puedeMover(jugador)) {
    refX = constrain(x, X_MIN, X_MAX);
    refY = constrain(y, Y_MIN, Y_MAX);
  }
  // Después del movimiento: el último pedido del tiro ya es la referencia.
  if (tiro) juego.tirar(tiro, ahora);
}

static bool cabezaQuieta() {
  return enlaceX() && fabsf(posX - refX) <= TOLERANCIA_MM && fabsf(posY - refY) <= TOLERANCIA_MM;
}

void setup() {
  Serial.begin(115200);
#if SIMULAR
  Serial.println("ESP32S3-Gato en modo SIMULAR: sin motores ni UART");
#else
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
  uart.begin(UART_BAUD, SERIAL_8N1, UART_RX, UART_TX);
#endif
  setupBle();
}

void loop() {
  static unsigned long lastSample = millis();
  static unsigned long lastState = 0;
  static unsigned long lastUart = 0;
  static unsigned long lastPose = 0;

  if (needAdvertising) {
    needAdvertising = false;
    if (bleServer->getConnectedCount() < CONFIG_BT_NIMBLE_MAX_CONNECTIONS) {
      BLEDevice::startAdvertising();
    }
  }

#if !SIMULAR
  leerUart();
#endif

  const unsigned long now = millis();
  if (now - lastSample < SAMPLE_MS) {
    delay(1);
    return;
  }
  const float dt = (now - lastSample) / 1000.0;
  lastSample = now;

  atenderComandos(now);
#if SIMULAR
  simularMaquina(dt);
#else
  controlY(dt);
  leerLdr();
#endif
  juego.actualizar(now, cabezaQuieta(), casillaIr());

#if !SIMULAR
  if (now - lastUart >= UART_MS) {
    lastUart = now;
    enviarReferenciaX();
  }
#endif
  if (now - lastState >= STATE_MS) {
    lastState = now;
    publicarEstado(now);
  }
  if (now - lastPose >= POSE_MS) {
    lastPose = now;
    reenviarPoses();
  }
}
