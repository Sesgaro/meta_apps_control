#include "ComunicacionS3.h"
#include "Config.h"
#include "SensoresIr.h"

namespace {

HardwareSerial &uart = Serial2;

// El control y la tarea de la UART comparten estos datos.
portMUX_TYPE bloqueoDatos = portMUX_INITIALIZER_UNLOCKED;

float referencia = 0.0f;
bool hayReferencia = false;
bool habilitadoS3 = false;
unsigned long ultimoComandoMs = 0;

// En grados del motor, como el control; se pasan a mm al enviarlos.
float referenciaTelemetria = 0.0f;
float posicionActual = 0.0f;
float salidaActual = 0.0f;

uint8_t checksum(const char *s, size_t n) {
  uint8_t c = 0;
  for (size_t i = 0; i < n; i++) c ^= (uint8_t)s[i];
  return c;
}

// Valida "$...*HH" y deja en [cuerpo] lo que va entre '$' y '*'.
bool validar(char *linea, char *&cuerpo) {
  if (linea[0] != '$') return false;
  char *asterisco = strrchr(linea, '*');
  if (asterisco == nullptr || strlen(asterisco) < 3) return false;
  const long esperado = strtol(asterisco + 1, nullptr, 16);
  *asterisco = '\0';
  cuerpo = linea + 1;
  return checksum(cuerpo, strlen(cuerpo)) == esperado;
}

// "$R,<referencia mm>,<habilitado>"
void procesar(char *linea) {
  char *cuerpo;
  if (!validar(linea, cuerpo)) return;
  if (strncmp(cuerpo, "R,", 2) != 0) return;

  char *fin;
  const float referenciaMm = strtof(cuerpo + 2, &fin);
  if (fin == cuerpo + 2 || *fin != ',') return;
  const bool habilitar = fin[1] == '1';
  if (!isfinite(referenciaMm) ||
      referenciaMm < MIN_POSITION_MM || referenciaMm > MAX_POSITION_MM) return;

  portENTER_CRITICAL(&bloqueoDatos);
  referencia = gradosDeMm(referenciaMm);
  hayReferencia = true;
  habilitadoS3 = habilitar;
  ultimoComandoMs = millis();
  portEXIT_CRITICAL(&bloqueoDatos);
}

void enviarTelemetria() {
  portENTER_CRITICAL(&bloqueoDatos);
  const float ref = referenciaTelemetria;
  const float posicion = posicionActual;
  const float salida = salidaActual;
  portEXIT_CRITICAL(&bloqueoDatos);

  char cuerpo[64];
  const int n = snprintf(cuerpo, sizeof(cuerpo), "T,%.1f,%.1f,%d,%u",
                         mmDeGrados(posicion), mmDeGrados(ref), (int)lroundf(salida),
                         SensoresIr::leerMascara());
  uart.printf("$%s*%02X\n", cuerpo, checksum(cuerpo, n));
}

void tareaComunicacion(void *) {
  char linea[48];
  size_t largo = 0;
  unsigned long ultimaTelemetria = 0;

  for (;;) {
    while (uart.available()) {
      const char c = uart.read();
      if (c == '\n' || c == '\r') {
        if (largo > 0) {
          linea[largo] = '\0';
          procesar(linea);
        }
        largo = 0;
      } else if (largo < sizeof(linea) - 1) {
        linea[largo++] = c;
      } else {
        largo = 0;  // línea demasiado larga: basura, se descarta
      }
    }

    const unsigned long ahora = millis();
    if (ahora - ultimaTelemetria >= UART_TELEMETRY_MS) {
      ultimaTelemetria = ahora;
      enviarTelemetria();
    }
    vTaskDelay(pdMS_TO_TICKS(2));
  }
}

}  // namespace

namespace ComunicacionS3 {

void iniciar() {
  SensoresIr::iniciar();
  uart.begin(UART_BAUD, SERIAL_8N1, UART_RX, UART_TX);
  // La UART corre en el otro núcleo para no bloquear el control.
  xTaskCreatePinnedToCore(tareaComunicacion, "UartS3", 4096, nullptr, 2, nullptr, 0);
  Serial.printf("[UART] Esperando a la ESP32-S3 en RX=%d TX=%d\n", UART_RX, UART_TX);
}

Comando leerComando() {
  portENTER_CRITICAL(&bloqueoDatos);
  const unsigned long ahora = millis();
  Comando comando{
      referencia,
      hayReferencia && habilitadoS3 && ahora - ultimoComandoMs <= STALE_COMMAND_TIMEOUT_MS};
  portEXIT_CRITICAL(&bloqueoDatos);
  return comando;
}

void actualizarTelemetria(float ref, float posicion, float, float salida) {
  // Guardamos las lecturas; la tarea de la UART las envía después.
  portENTER_CRITICAL(&bloqueoDatos);
  referenciaTelemetria = ref;
  posicionActual = posicion;
  salidaActual = salida;
  portEXIT_CRITICAL(&bloqueoDatos);
}

}  // namespace ComunicacionS3
