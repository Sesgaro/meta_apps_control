// VERSION NC: GPIO15 con INPUT_PULLUP. Reposo=LOW; presionado=HIGH (Y_PRESIONADO).
// ESP32-S3: eje Y, juego de gato, BLE y matriz LDR.
// ESP32D: eje X, conectado mediante UART.
//
// Motor Y con BTS7960:
// L_EN -> GPIO14
// R_EN -> GPIO12
// LPWM -> GPIO4
// RPWM -> GPIO5
//
// Encoder Y:
// A -> GPIO21
// B -> GPIO47
//
// Switch normalmente cerrado (NC) de Y:
// GPIO15 -> NC del switch
// GND    -> COM del switch
//
// UART:
// S3 TX GPIO17 -> ESP32D RX GPIO16
// S3 RX GPIO18 <- ESP32D TX GPIO17
// GND comun.
//
// Al encender busca el switch de Y y establece cero.
// Espera tambien a que X termine su homing.
// Requiere el archivo gato.h del proyecto original.

#include <Arduino.h>
#include <math.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <driver/pulse_cnt.h>
#include <driver/gpio.h>
#include <Preferences.h>

#include "gato.h"

// ================= HOMING =================

class HomingSwitch {
 public:
  enum Estado {
    BUSCANDO,
    LISTO,
    FALLO
  };

  void iniciar(unsigned long ahora, bool activado) {
    inicio = cambio = ahora;
    lectura = activado;
    estado = BUSCANDO;
  }

  int actualizar(
      unsigned long ahora,
      bool activado,
      int magnitud,
      unsigned long limiteMs
  ) {
    if (estado != BUSCANDO) return 0;

    if (activado != lectura) {
      lectura = activado;
      cambio = ahora;
    }

    if (ahora - inicio >= limiteMs) {
      estado = FALLO;
      return 0;
    }

    // Detener al detectar el switch.
    // Confirmar la activacion durante 30 ms.
    if (lectura) {
      if (ahora - cambio >= 30) {
        estado = LISTO;
      }
      return 0;
    }

    // Esperar que el switch este liberado de forma estable antes de mover.
    if (ahora - cambio < 30) return 0;

    // Movimiento hacia Y minima.
    return -magnitud;
  }

  Estado estado = BUSCANDO;

 private:
  unsigned long inicio = 0;
  unsigned long cambio = 0;
  bool lectura = false;
};

// Ajustar al minimo PWM que mueva el mecanismo.
constexpr int HOMING_PWM = 255;   // a tope: con menos el husillo no se movía
constexpr unsigned long HOMING_TIMEOUT_MS = 60000;

HomingSwitch homing;
bool homingAnunciado = false;

// ================= BLE =================

#define DEVICE_NAME       "ESP32S3-Gato"
#define SERVICE_UUID      "7a1d1000-5e2f-4b8a-9c3d-2f1e0a6b4c11"
#define COMMAND_CHAR_UUID "7a1d1001-5e2f-4b8a-9c3d-2f1e0a6b4c11"
#define STATE_CHAR_UUID   "7a1d1002-5e2f-4b8a-9c3d-2f1e0a6b4c11"
#define POSE_CHAR_UUID    "7a1d1003-5e2f-4b8a-9c3d-2f1e0a6b4c11"

// ================= PINES MOTOR Y =================

#define EN_L  14
#define EN_R  12
#define PWM_L 4
#define PWM_R 5
#define ENC_A 21
#define ENC_B 47

// ================= UART =================

#define UART_TX 17
#define UART_RX 18

const unsigned long UART_BAUD = 115200;

// ================= SWITCH Y =================

constexpr int CERO_Y_PIN = 15;
// Nivel del pin con el switch presionado (en reposo, el contrario).
constexpr int Y_PRESIONADO = HIGH;   // NC: al presionarlo se abre el contacto
constexpr unsigned long CERO_Y_ANTIRREBOTE_MS = 30;

static int ceroYLectura = !Y_PRESIONADO;
static int ceroYEstable = !Y_PRESIONADO;
static unsigned long ceroYCambioMs = 0;

// ================= LDR =================

// Casilla = fila * 3 + columna.
// Fila 0 = Y minima; columna 0 = X minima.
// Mapeado: GPIO13 en (26, 347) y GPIO11 en (113, 347), los mas cercanos al
// (0,0): esa es la fila 0, y en cada fila el GPIO mas alto queda en X minima.
// El sensor central (GPIO8) esta en el centro del tablero, (113, 670).
constexpr int LDR_PINES[9] = {
  13, 11, 10,
  9, 8, 7,
  6, 2, 1
};

// Cada sensor lee distinto sin luz (~500 a ~1300) y el LED lo baja ~110:
// detecta cuando baja LDR_BAJADA de su propia base (su lectura sin LED).
// Se cambia en vivo con "t <bajada>" en el monitor.
static int ldrBajada = 70;
static int lecturaLdr[9];
static float baseLdr[9];   // 0 = sin medir todavia
static float filtradaLdr[9];
static uint8_t cuentaLdr[9];  // lecturas seguidas al otro lado del umbral
constexpr uint8_t LDR_CONFIRMA = 10;   // 10 lecturas (100 ms) para cambiar

static uint16_t mascaraLdr = 0;

static void iniciarLdr() {
  analogReadResolution(12);

  for (int pin : LDR_PINES) {
    pinMode(pin, INPUT);
    analogSetPinAttenuation(pin, ADC_11db);
  }
}

static uint16_t leerMascaraLdr() {
  uint16_t mascara = 0;

  for (int i = 0; i < 9; ++i) {
    // Promedio de 4 lecturas: el ADC brinca ~70 entre lecturas.
    int suma = 0;
    for (int k = 0; k < 4; ++k) suma += analogRead(LDR_PINES[i]);
    // Y filtrada ~100 ms: GPIO13 brinca ~500 con el LED encima.
    filtradaLdr[i] = filtradaLdr[i] == 0.0f ? suma / 4.0f
                                            : filtradaLdr[i] + (suma / 4.0f - filtradaLdr[i]) * 0.1f;
    const float lectura = filtradaLdr[i];
    lecturaLdr[i] = (int)lectura;

    if (baseLdr[i] == 0.0f) baseLdr[i] = lectura;
    const bool antes = mascaraLdr & (1u << i);
    // Histeresis: entra al bajar ldrBajada, sale al subir a la mitad, y el
    // cambio debe sostenerse LDR_CONFIRMA lecturas (GPIO6 brinca ~100 solo).
    const float bajada = baseLdr[i] - lectura;
    const bool otroLado = antes ? bajada < ldrBajada / 2.0f : bajada > ldrBajada;
    cuentaLdr[i] = otroLado ? cuentaLdr[i] + 1 : 0;
    const bool detecta = cuentaLdr[i] >= LDR_CONFIRMA ? !antes : antes;
    if (cuentaLdr[i] >= LDR_CONFIRMA) cuentaLdr[i] = 0;

    // La base sigue la luz del cuarto despacio (~20 s) en los dos sentidos: si
    // subiera rapido, la sombra del eje la levantaria y al irse la sombra
    // pareceria un LED. Los primeros 5 s se asienta rapido.
    baseLdr[i] += (lectura - baseLdr[i]) * (millis() < 5000 ? 0.05f : 0.0005f);

    if (detecta) {
      mascara |= static_cast<uint16_t>(1u << i);
    }
  }

  return mascara;
}

// ================= GEOMETRIA =================

// Husillo T8 del eje Y.
const float MM_POR_VUELTA_Y = 8.0f;

// El cero real se establece al completar el homing.
const float X_MIN = 0.0f;
// Medido: X topa en 256.8 mm, menos 10 (igual que en firmware/eje_x).
const float X_MAX = 246.8f;

const float Y_MIN = 0.0f;
// Medido con "l": Y topa en 906.7, menos 10.
const float Y_MAX = 896.7f;

// Sensores mapeados: columnas en x = 26, 113, 200 y filas en y = 347, 670,
// 993 (Y no esta en mm reales: 323 por cada 85 mm).
const float X_CASILLA0 = 26.0f;
const float X_PASO = 87.0f;

const float Y_CASILLA0 = 347.0f;
const float Y_PASO = 323.0f;

const float TOLERANCIA_MM = 2.0f;

// ================= JUEGO =================

gato::Juego juego([] {
  gato::Config c;

  c.turnoMs = 20000;
  c.quietoMs = 300;
  c.resolverMaxMs = 12000;

  return c;
}());

// ================= PROTOCOLO BLE =================

// Comandos:
// C0 jugador xLo xHi yLo yHi
// Coordenadas int16 en decimas de milimetro.
//
// C1 01: partida nueva.
// C2 jugador: tirar.
//
// Estado de 45 bytes:
// [0]     C6
// [1]     fase
// [2]     turno
// [3..4]  tiempo restante, decimas de segundo
// [5..13] tablero
// [14]    ganador
// [15]    ultimo resultado
// [16]    ultima casilla
// [17]    ultimo jugador
// [18..25] posX, posY, refX, refY
// [26]    casilla detectada por los LDR
// [27]    bit0 = enlace UART con X
// [28..35] limites X/Y
// [36..43] geometria de casillas
// [44]    duracion del turno en segundos

constexpr uint8_t CMD_MOVER = 0xC0;
constexpr uint8_t CMD_JUEGO = 0xC1;
constexpr uint8_t CMD_TIRAR = 0xC2;

constexpr uint8_t STATE_HEADER = 0xC6;
constexpr size_t STATE_LENGTH = 45;

constexpr uint8_t POSE_HEADER = 0xD0;
constexpr size_t POSE_LENGTH = 30;

constexpr unsigned long POSE_MS = 50;

static BLEServer *bleServer = nullptr;
static BLECharacteristic *stateChar = nullptr;
static BLECharacteristic *poseChar = nullptr;

static volatile bool needAdvertising = false;

// Compartido entre BLE y loop.
static portMUX_TYPE bloqueo = portMUX_INITIALIZER_UNLOCKED;

static float pedidoX = 0.0f;
static float pedidoY = 0.0f;
static uint8_t pedidoJugador = 0;

static bool hayPedido = false;
static bool pedirPartida = false;
static uint8_t tiroJugador = 0;

static uint8_t poses[2][POSE_LENGTH];
static bool poseNueva[2] = {false, false};

// Referencias en milimetros.
static float refX = 0.0f;
static float refY = 0.0f;

// ================= PID DEL EJE Y =================

const float ENC_PPR = 11.0f;
const float GEAR_RATIO = 34.0f;
const float CPR = ENC_PPR * GEAR_RATIO * 4.0f;

const unsigned long SAMPLE_MS = 10;
const unsigned long STATE_MS = 100;
const unsigned long UART_MS = 50;
const unsigned long UART_TIMEOUT_MS = 500;

// Ganancias conservadas del codigo original de la S3.
const float kp = 7.6f;
const float ki = 51.0f;
const float kd = 0.135f;

const float DEADBAND = 0.5f;
const float U_FRIC = 12.0f;
const float I_ZONE = 5.0f;

static float e_integral = 0.0f;
static float last_pos = 0.0f;
static float d_filt = 0.0f;

static float posY = 0.0f;
static float pwmY = 0.0f;

static pcnt_unit_handle_t encUnit = nullptr;

static float gradosDeMm(float mm) {
  return mm * 360.0f / MM_POR_VUELTA_Y;
}

static float mmDeGrados(float grados) {
  return grados * MM_POR_VUELTA_Y / 360.0f;
}

// ================= ENCODER Y =================

static long readEncoder() {
  int c = 0;
  pcnt_unit_get_count(encUnit, &c);
  // Signo invertido: con PWM negativo (hacia el switch) Y debe bajar.
  return -c;
}

static void setupEncoder() {
  pcnt_unit_config_t unit = {};

  unit.low_limit = -30000;
  unit.high_limit = 30000;
  unit.flags.accum_count = 1;

  ESP_ERROR_CHECK(
      pcnt_new_unit(&unit, &encUnit)
  );

  pcnt_glitch_filter_config_t filter = {};
  filter.max_glitch_ns = 2000;

  ESP_ERROR_CHECK(
      pcnt_unit_set_glitch_filter(encUnit, &filter)
  );

  pcnt_chan_config_t a = {};
  a.edge_gpio_num = ENC_A;
  a.level_gpio_num = ENC_B;

  pcnt_channel_handle_t chA;

  ESP_ERROR_CHECK(
      pcnt_new_channel(encUnit, &a, &chA)
  );

  pcnt_chan_config_t b = {};
  b.edge_gpio_num = ENC_B;
  b.level_gpio_num = ENC_A;

  pcnt_channel_handle_t chB;

  ESP_ERROR_CHECK(
      pcnt_new_channel(encUnit, &b, &chB)
  );

  pcnt_channel_set_edge_action(
      chA,
      PCNT_CHANNEL_EDGE_ACTION_DECREASE,
      PCNT_CHANNEL_EDGE_ACTION_INCREASE
  );

  pcnt_channel_set_level_action(
      chA,
      PCNT_CHANNEL_LEVEL_ACTION_KEEP,
      PCNT_CHANNEL_LEVEL_ACTION_INVERSE
  );

  pcnt_channel_set_edge_action(
      chB,
      PCNT_CHANNEL_EDGE_ACTION_INCREASE,
      PCNT_CHANNEL_EDGE_ACTION_DECREASE
  );

  pcnt_channel_set_level_action(
      chB,
      PCNT_CHANNEL_LEVEL_ACTION_KEEP,
      PCNT_CHANNEL_LEVEL_ACTION_INVERSE
  );

  pcnt_unit_add_watch_point(encUnit, unit.low_limit);
  pcnt_unit_add_watch_point(encUnit, unit.high_limit);

  gpio_pullup_en((gpio_num_t)ENC_A);
  gpio_pullup_en((gpio_num_t)ENC_B);

  ESP_ERROR_CHECK(pcnt_unit_enable(encUnit));
  ESP_ERROR_CHECK(pcnt_unit_clear_count(encUnit));
  ESP_ERROR_CHECK(pcnt_unit_start(encUnit));
}

// ================= DRIVER Y =================

// Primero se apaga el lado contrario: nunca quedan las dos PWM activas.
static void drive(float u) {
  if (u > 0.0f) {
    analogWrite(PWM_L, 0);
    analogWrite(PWM_R, (int)u);
  } else {
    analogWrite(PWM_R, 0);
    analogWrite(PWM_L, (int)-u);
  }
}

// ================= PUESTA A CERO Y =================

// Un cero por activacion estable del switch.
// Debe liberarse antes de aceptar otra activacion.
static bool atenderCeroY(unsigned long ahora) {
  const int lectura = digitalRead(CERO_Y_PIN);

  if (lectura != ceroYLectura) {
    ceroYLectura = lectura;
    ceroYCambioMs = ahora;
  }

  if (lectura == ceroYEstable ||
      ahora - ceroYCambioMs < CERO_Y_ANTIRREBOTE_MS) {
    return false;
  }

  ceroYEstable = lectura;

  if (ceroYEstable != Y_PRESIONADO) return false;

  drive(0);

  ESP_ERROR_CHECK(
      pcnt_unit_clear_count(encUnit)
  );

  posY = 0.0f;
  refY = 0.0f;
  pwmY = 0.0f;

  e_integral = 0.0f;
  last_pos = 0.0f;
  d_filt = 0.0f;

  Serial.println(
      "[CERO Y] Switch activado: posicion y referencia = 0 mm"
  );

  return true;
}

// ================= HOMING AUTOMATICO Y =================

static bool atenderHomingY(unsigned long ahora) {
  if (homing.estado == HomingSwitch::LISTO) {
    return false;
  }

  const int orden = homing.actualizar(
      ahora,
      digitalRead(CERO_Y_PIN) == Y_PRESIONADO,
      HOMING_PWM,
      HOMING_TIMEOUT_MS
  );

  drive(orden);
  pwmY = orden;

  // Durante el homing todavia no existe un cero confirmado.
  posY = mmDeGrados(
      readEncoder() / CPR * 360.0f
  );

  if (homing.estado == HomingSwitch::LISTO) {
    drive(0);

    ESP_ERROR_CHECK(
        pcnt_unit_clear_count(encUnit)
    );

    posY = 0.0f;
    refY = 0.0f;
    pwmY = 0.0f;

    e_integral = 0.0f;
    last_pos = 0.0f;
    d_filt = 0.0f;

    ceroYLectura = Y_PRESIONADO;
    ceroYEstable = Y_PRESIONADO;
    ceroYCambioMs = ahora;

    Serial.println("[HOME Y] Listo: aqui es 0 mm.");

  } else if (homing.estado == HomingSwitch::FALLO &&
             !homingAnunciado) {
    homingAnunciado = true;

    Serial.println(
        "[HOME Y] ERROR: switch no encontrado en 60 s. "
        "Reiniciar para reintentar."
    );
  }

  return true;
}

// ================= MEDIR LIMITE Y (prueba) =================

// "l" en el monitor serie, con el homing listo: Y avanza alejandose del
// switch hasta que el motor se detiene contra el tope y reporta esa posicion.
constexpr int MEDIR_PWM = 140;
constexpr unsigned long MEDIR_QUIETO_MS = 1500;   // el husillo se atora a ratos a medio camino
static bool midiendoY = false;
static unsigned long medirInicioMs = 0;
static unsigned long medirAvanceMs = 0;
static float medirMaxY = 0.0f;

static bool atenderMedirY(unsigned long ahora) {
  if (!midiendoY) return false;

  const float grados = readEncoder() / CPR * 360.0f;
  posY = mmDeGrados(grados);

  if (posY > medirMaxY + 0.2f) {
    medirMaxY = posY;
    medirAvanceMs = ahora;
  }

  if (ahora - medirAvanceMs < MEDIR_QUIETO_MS &&
      ahora - medirInicioMs < 120000) {
    drive(MEDIR_PWM);
    pwmY = MEDIR_PWM;
    return true;
  }

  drive(0);
  pwmY = 0.0f;
  midiendoY = false;

  // Se queda donde paro: el PID no la regresa de golpe.
  refY = posY;
  e_integral = 0.0f;
  last_pos = grados;
  d_filt = 0.0f;

  Serial.printf(
      "[LIMITE Y] el motor se detuvo en %.1f mm -> limite = %.1f mm\n",
      posY, posY - 10.0f);
  return true;
}

// ================= CONTROL PID Y =================

static void controlY(float dt) {
  const float pos =
      readEncoder() / CPR * 360.0f;

  const float e = gradosDeMm(refY) - pos;

  d_filt =
      0.8f * d_filt +
      0.2f * (pos - last_pos) / dt;

  last_pos = pos;

  float u;

  if (fabsf(e) <= DEADBAND) {
    u = -(kd * d_filt);

  } else {
    u =
        kp * e +
        ki * e_integral -
        kd * d_filt +
        (e > 0.0f ? U_FRIC : -U_FRIC);

    const bool saturado =
        (u >= 255.0f && e > 0.0f) ||
        (u <= -255.0f && e < 0.0f);

    if (fabsf(e) >= I_ZONE) {
      e_integral = 0.0f;
    } else if (!saturado) {
      e_integral += e * dt;
    }
  }

  u = constrain(u, -255.0f, 255.0f);

  // Con el switch presionado, permitir alejarse,
  // pero bloquear el movimiento hacia Y negativa.
  if ((digitalRead(CERO_Y_PIN) == Y_PRESIONADO ||
       ceroYEstable == Y_PRESIONADO) &&
      u < 0.0f) {
    u = 0.0f;
    e_integral = 0.0f;
  }

  drive(u);

  posY = mmDeGrados(pos);
  pwmY = u;
}

// ================= UART CON EJE X =================

// Recibe:
// $T,<posX>,<refX>,<pwm>*HH
// $H,<estado>*HH
//
// Estados homing:
// 0 buscando, 1 listo, 2 fallo.

static HardwareSerial &uart = Serial1;
static uint32_t bytesRx = 0;   // bytes que llegan de X (diagnostico del enlace)

static float posX = 0.0f;
static bool homingXListo = false;

static unsigned long ultimaTelemetriaX = 0;
static bool hayTelemetriaX = false;

static uint8_t checksum(const char *s, size_t n) {
  uint8_t c = 0;

  for (size_t i = 0; i < n; i++) {
    c ^= (uint8_t)s[i];
  }

  return c;
}

static bool enlaceX() {
  return hayTelemetriaX &&
         millis() - ultimaTelemetriaX <= UART_TIMEOUT_MS;
}

static void procesarLineaX(char *linea) {
  if (linea[0] != '$') return;

  char *asterisco = strrchr(linea, '*');

  if (asterisco == nullptr ||
      strlen(asterisco) < 3) {
    return;
  }

  const long esperado =
      strtol(asterisco + 1, nullptr, 16);

  *asterisco = '\0';

  char *cuerpo = linea + 1;

  if (checksum(cuerpo, strlen(cuerpo)) != esperado) {
    return;
  }

  if (strncmp(cuerpo, "H,", 2) == 0) {
    homingXListo = strcmp(cuerpo, "H,1") == 0;
    return;
  }

  if (strncmp(cuerpo, "T,", 2) != 0) return;

  float pos;
  float ref;
  int pwm;

  if (sscanf(
          cuerpo + 2,
          "%f,%f,%d",
          &pos,
          &ref,
          &pwm
      ) != 3) {
    return;
  }

  posX = pos;
  ultimaTelemetriaX = millis();
  hayTelemetriaX = true;
}

static void leerUart() {
  static char linea[64];
  static size_t largo = 0;

  while (uart.available()) {
    const char c = uart.read();
    bytesRx++;

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

static void enviarReferenciaX() {
  char cuerpo[32];

  const bool habilitar =
      homing.estado == HomingSwitch::LISTO &&
      homingXListo &&
      enlaceX();

  const int n = snprintf(
      cuerpo,
      sizeof(cuerpo),
      "R,%.1f,%d",
      refX,
      habilitar ? 1 : 0
  );

  uart.printf(
      "$%s*%02X\n",
      cuerpo,
      checksum(cuerpo, n)
  );
}

// ================= ESTADO BLE =================

static void put16(uint8_t *p, int16_t v) {
  p[0] = (uint8_t)v;
  p[1] = (uint8_t)(v >> 8);
}

static int16_t decimas(float mm) {
  return (int16_t)constrain(
      lroundf(mm * 10.0f),
      -32768L,
      32767L
  );
}

// Si detectan varios (luz que se cuela al vecino, un sensor ruidoso), gana el
// que mas bajo: el LED esta encima de ese.
static uint8_t casillaLdr() {
  uint8_t mejor = gato::SIN_CASILLA;
  float mayor = 0.0f;
  for (uint8_t i = 0; i < 9; ++i) {
    const float bajada = baseLdr[i] - lecturaLdr[i];
    if ((mascaraLdr & (1u << i)) && bajada > mayor) {
      mayor = bajada;
      mejor = i;
    }
  }
  return mejor;
}

static void publicarEstado(unsigned long ahora) {
  uint8_t f[STATE_LENGTH];

  f[0] = STATE_HEADER;
  f[1] = juego.fase;
  f[2] = juego.turno;

  put16(
      f + 3,
      (int16_t)min<uint32_t>(
          juego.restanteMs(ahora) / 100,
          32767
      )
  );

  memcpy(f + 5, juego.tablero, 9);

  f[14] = juego.ganadorPartida;
  f[15] = juego.ultimo;
  f[16] = juego.ultimaCasilla;
  f[17] = juego.ultimoJugador;

  put16(f + 18, decimas(posX));
  put16(f + 20, decimas(posY));
  put16(f + 22, decimas(refX));
  put16(f + 24, decimas(refY));

  f[26] = casillaLdr();
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

  if (bleServer->getConnectedCount() > 0) {
    stateChar->notify();
  }
}

// ================= RECEPCION BLE =================

class CommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    const uint8_t *d = characteristic->getData();
    const size_t len = characteristic->getLength();

    if (len == 6 && d[0] == CMD_MOVER) {
      const int16_t x =
          (int16_t)(d[2] | (d[3] << 8));

      const int16_t y =
          (int16_t)(d[4] | (d[5] << 8));

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

    } else if (len == 2 &&
               d[0] == CMD_JUEGO &&
               d[1] == 0x01) {
      portENTER_CRITICAL(&bloqueo);
      pedirPartida = true;
      portEXIT_CRITICAL(&bloqueo);
    }
  }
};

class PoseCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    const uint8_t *d = characteristic->getData();

    if (characteristic->getLength() != POSE_LENGTH ||
        d[0] != POSE_HEADER) {
      return;
    }

    if (d[1] != gato::JUGADOR_X &&
        d[1] != gato::JUGADOR_O) {
      return;
    }

    portENTER_CRITICAL(&bloqueo);

    memcpy(
        poses[d[1] - 1],
        d,
        POSE_LENGTH
    );

    poseNueva[d[1] - 1] = true;

    portEXIT_CRITICAL(&bloqueo);
  }
};

// ================= REENVIO DE POSES =================

static void reenviarPoses() {
  for (int i = 0; i < 2; i++) {
    uint8_t f[POSE_LENGTH];

    portENTER_CRITICAL(&bloqueo);

    const bool nueva = poseNueva[i];

    if (nueva) {
      memcpy(f, poses[i], POSE_LENGTH);
    }

    poseNueva[i] = false;

    portEXIT_CRITICAL(&bloqueo);

    if (!nueva ||
        bleServer->getConnectedCount() == 0) {
      continue;
    }

    poseChar->setValue(f, POSE_LENGTH);
    poseChar->notify();
  }
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *) override {
    needAdvertising = true;
  }

  void onDisconnect(BLEServer *) override {
    needAdvertising = true;
  }
};

// ================= INICIALIZACION BLE =================

static void setupBle() {
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(100);

  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  BLEService *service =
      bleServer->createService(SERVICE_UUID);

  BLECharacteristic *cmd =
      service->createCharacteristic(
          COMMAND_CHAR_UUID,
          BLECharacteristic::PROPERTY_WRITE_NR
      );

  cmd->setCallbacks(new CommandCallbacks());

  stateChar = service->createCharacteristic(
      STATE_CHAR_UUID,
      BLECharacteristic::PROPERTY_READ |
      BLECharacteristic::PROPERTY_NOTIFY
  );

  poseChar = service->createCharacteristic(
      POSE_CHAR_UUID,
      BLECharacteristic::PROPERTY_WRITE_NR |
      BLECharacteristic::PROPERTY_NOTIFY
  );

  poseChar->setCallbacks(new PoseCallbacks());

  service->start();

  BLEAdvertising *advertising =
      BLEDevice::getAdvertising();

  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);

  BLEDevice::startAdvertising();
}

// ================= COMANDOS DEL JUEGO =================

static void atenderComandos(unsigned long ahora) {
  portENTER_CRITICAL(&bloqueo);

  const bool partida = pedirPartida;
  const bool pedido = hayPedido;

  const uint8_t jugador = pedidoJugador;
  const float x = pedidoX;
  const float y = pedidoY;
  const uint8_t tiro = tiroJugador;

  pedirPartida = false;
  hayPedido = false;
  tiroJugador = 0;

  portEXIT_CRITICAL(&bloqueo);

  // No aceptar comandos hasta que ambos ejes esten listos.
  if (homing.estado != HomingSwitch::LISTO ||
      !homingXListo ||
      !enlaceX()) {
    return;
  }

  if (partida) {
    juego.nueva(ahora);
  }

  if (pedido && juego.puedeMover(jugador)) {
    refX = constrain(x, X_MIN, X_MAX);
    refY = constrain(y, Y_MIN, Y_MAX);
  }

  if (tiro) {
    juego.tirar(tiro, ahora);
  }
}

static bool cabezaQuieta() {
  return
      homing.estado == HomingSwitch::LISTO &&
      homingXListo &&
      enlaceX() &&
      fabsf(posX - refX) <= TOLERANCIA_MM &&
      fabsf(posY - refY) <= TOLERANCIA_MM;
}

// ================= MONITOR SERIE: MAPEO DE SENSORES =================

// Comandos (una linea + Enter; el monitor debe mandar fin de linea):
//   r          reinicia la placa
//   l          mide el tope de Y
//   g <x> <y>  lleva la cabeza a (x, y) mm (x va por UART a la placa de X)
//   s <n> [x]  guarda la posicion actual como coordenada del sensor n (0-8);
//              [x] si no hay enlace con la placa de X (se lee en su monitor)
//   p          imprime las coordenadas guardadas
//   t <n>      cuanto debe bajar un LDR de su base para detectar (def. 70)
// Ademas avisa cada vez que un sensor empieza o deja de detectar.

static Preferences prefs;
static float coordLdr[9][2];   // x, y en mm; NAN = sin mapear

static void cargarCoordenadas() {
  prefs.begin("ldr", false);
  if (prefs.getBytes("coord", coordLdr, sizeof(coordLdr)) != sizeof(coordLdr)) {
    for (auto &c : coordLdr) c[0] = c[1] = NAN;
  }
}

static void imprimirCoordenadas() {
  Serial.println("[MAPA] sensor: x, y (mm)");
  for (int i = 0; i < 9; ++i) {
    if (isnan(coordLdr[i][0])) {
      Serial.printf("[MAPA] %d (GPIO%d): sin mapear\n", i, LDR_PINES[i]);
    } else {
      Serial.printf("[MAPA] %d (GPIO%d): %.1f, %.1f\n",
                    i, LDR_PINES[i], coordLdr[i][0], coordLdr[i][1]);
    }
  }
}

static void ejecutarComando(const char *linea) {
  float a, b;
  int n;

  if (strcmp(linea, "r") == 0) {
    ESP.restart();

  } else if (strcmp(linea, "l") == 0) {
    if (homing.estado == HomingSwitch::LISTO && !midiendoY) {
      midiendoY = true;
      medirInicioMs = medirAvanceMs = millis();
      medirMaxY = posY;
      Serial.println("[LIMITE Y] Midiendo: avanzando hasta el tope...");
    }

  } else if (strcmp(linea, "p") == 0) {
    imprimirCoordenadas();

  } else if (sscanf(linea, "t %f", &a) == 1 && a > 0) {
    ldrBajada = (int)a;
    Serial.printf("[LDR] Detecta al bajar %d de su base\n", ldrBajada);

  } else if (sscanf(linea, "g %f %f", &a, &b) == 2) {
    if (homing.estado != HomingSwitch::LISTO) {
      Serial.println("[MAPA] Espera a que termine el homing de Y.");
      return;
    }
    refX = constrain(a, X_MIN, X_MAX);
    refY = constrain(b, Y_MIN, Y_MAX);
    Serial.printf("[MAPA] Yendo a x=%.1f y=%.1f%s\n", refX, refY,
                  enlaceX() && homingXListo ? "" : " (X sin enlace: muevelo desde su monitor con x <mm>)");

  } else if (const int k = sscanf(linea, "s %d %f", &n, &a); k >= 1 && n >= 0 && n < 9) {
    if (k == 1 && !enlaceX()) {
      Serial.println("[MAPA] Sin enlace con X: usa s <n> <x>, con la x de su monitor.");
      return;
    }
    coordLdr[n][0] = k == 2 ? a : posX;
    coordLdr[n][1] = posY;
    prefs.putBytes("coord", coordLdr, sizeof(coordLdr));
    Serial.printf("[MAPA] Sensor %d guardado en x=%.1f y=%.1f\n", n, coordLdr[n][0], coordLdr[n][1]);

  } else {
    Serial.printf("[MAPA] Comando no reconocido: %s\n", linea);
  }
}

static void atenderMonitor() {
  static char linea[32];
  static size_t largo = 0;

  while (Serial.available()) {
    const char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (largo > 0) {
        linea[largo] = '\0';
        ejecutarComando(linea);
      }
      largo = 0;
    } else if (largo < sizeof(linea) - 1) {
      linea[largo++] = c;
    }
  }
}

// Que sensor empieza o deja de detectar, con su lectura y donde esta la cabeza.
static void avisarLdr(uint16_t antes, uint16_t ahora) {
  for (int i = 0; i < 9; ++i) {
    const bool on = ahora & (1u << i);
    if (on == bool(antes & (1u << i))) continue;
    Serial.printf("[LDR] Sensor %d (GPIO%d) %s, lectura %d, cabeza en x=%.1f y=%.1f\n",
                  i, LDR_PINES[i], on ? "DETECTA" : "deja de detectar",
                  analogRead(LDR_PINES[i]), posX, posY);
  }
}

// ================= INICIALIZACION =================

void setup() {
  Serial.begin(115200);
  // Hasta 3 s para abrir el monitor por USB y alcanzar a ver el homing.
  while (!Serial && millis() < 3000) delay(10);

  pinMode(EN_R, OUTPUT);
  pinMode(EN_L, OUTPUT);
  pinMode(PWM_L, OUTPUT);
  pinMode(PWM_R, OUTPUT);

  digitalWrite(EN_R, HIGH);
  digitalWrite(EN_L, HIGH);

  analogWriteFrequency(PWM_L, 20000);
  analogWriteFrequency(PWM_R, 20000);

  drive(0);

  setupEncoder();

  pinMode(CERO_Y_PIN, INPUT_PULLUP);

  ceroYLectura = digitalRead(CERO_Y_PIN);
  ceroYCambioMs = millis();

  iniciarLdr();
  cargarCoordenadas();

  uart.begin(
      UART_BAUD,
      SERIAL_8N1,
      UART_RX,
      UART_TX
  );

  setupBle();

  homing.iniciar(
      millis(),
      digitalRead(CERO_Y_PIN) == Y_PRESIONADO
  );

  Serial.println(
      "[HOME Y] Buscando switch en GPIO15..."
  );
}

// ================= BUCLE PRINCIPAL =================

void loop() {
  static unsigned long lastSample = millis();
  static unsigned long lastState = 0;
  static unsigned long lastUart = 0;
  static unsigned long lastPose = 0;

  if (needAdvertising) {
    needAdvertising = false;

    if (bleServer->getConnectedCount() <
        CONFIG_BT_NIMBLE_MAX_CONNECTIONS) {
      BLEDevice::startAdvertising();
    }
  }

  // Desde el monitor serie: "r" reinicia la placa.
  atenderMonitor();

  leerUart();

  const unsigned long now = millis();

  if (now - lastSample < SAMPLE_MS) {
    delay(1);
    return;
  }

  const float dt =
      (now - lastSample) / 1000.0f;

  lastSample = now;

  atenderComandos(now);

  if (!atenderHomingY(now) &&
      !atenderMedirY(now) &&
      !atenderCeroY(now)) {
    controlY(dt);
  }

  const uint16_t mascaraAntes = mascaraLdr;
  mascaraLdr = leerMascaraLdr();
  avisarLdr(mascaraAntes, mascaraLdr);

  if (homing.estado == HomingSwitch::LISTO &&
      homingXListo &&
      enlaceX()) {
    juego.actualizar(
        now,
        cabezaQuieta(),
        casillaLdr()
    );
  }

  if (now - lastUart >= UART_MS) {
    lastUart = now;
    enviarReferenciaX();
  }

  // Estado cada segundo en el monitor serie (homing 0 buscando, 1 listo, 2 fallo).
  static unsigned long lastLog = 0;
  if (now - lastLog >= 1000) {
    lastLog = now;
    Serial.printf("[Y] posX=%.1f posY=%.1f refX=%.1f refY=%.1f rx=%lu homing=%d switch=%s pwmY=%.0f homingX=%d enlaceX=%d\n",
                  posX, posY, refX, refY, (unsigned long)bytesRx,
                  homing.estado, digitalRead(CERO_Y_PIN) == Y_PRESIONADO ? "presionado" : "suelto",
                  pwmY, homingXListo, enlaceX());
    int baja[9];
    for (int i = 0; i < 9; ++i) baja[i] = (int)baseLdr[i] - lecturaLdr[i];
    Serial.printf("[LDR] bajada %d %d %d | %d %d %d | %d %d %d (detecta con mas de %d)\n",
                  baja[0], baja[1], baja[2], baja[3], baja[4], baja[5],
                  baja[6], baja[7], baja[8], ldrBajada);
  }

  if (now - lastState >= STATE_MS) {
    lastState = now;
    publicarEstado(now);
  }

  if (now - lastPose >= POSE_MS) {
    lastPose = now;
    reenviarPoses();
  }
}