#include <Arduino.h>
#include "Config.h"
#include "ComunicacionS3.h"
#include "ControlPosicion.h"

namespace {
volatile long encoderCount = 0;
portMUX_TYPE bloqueoEncoder = portMUX_INITIALIZER_UNLOCKED;

void IRAM_ATTR leerEncoder() {
  const int sentido = digitalRead(ENC_B) == HIGH ? 1 : -1;
  portENTER_CRITICAL_ISR(&bloqueoEncoder);
  encoderCount += sentido;
  portEXIT_CRITICAL_ISR(&bloqueoEncoder);
}

long leerConteo() {
  portENTER_CRITICAL(&bloqueoEncoder);
  const long conteo = encoderCount;
  portEXIT_CRITICAL(&bloqueoEncoder);
  return conteo;
}

void detenerMotor() {
  ledcWrite(CANAL_RPWM, 0);
  ledcWrite(CANAL_LPWM, 0);
  digitalWrite(R_EN, LOW);
  digitalWrite(L_EN, LOW);
}

void moverMotor(float salida) {
  const int pwm = lroundf(fabsf(salida));
  if (pwm == 0) {
    detenerMotor();
    return;
  }
  ledcWrite(CANAL_RPWM, 0);
  ledcWrite(CANAL_LPWM, 0);
  digitalWrite(R_EN, HIGH);
  digitalWrite(L_EN, HIGH);
  if (salida > 0.0f) {
    ledcWrite(CANAL_RPWM, pwm);
  } else {
    ledcWrite(CANAL_LPWM, pwm);
  }
}

void tareaControl(void*) {
  TickType_t ultimoDespertar = xTaskGetTickCount();
  const TickType_t periodo = pdMS_TO_TICKS(CONTROL_PERIOD_MS);
  const float dt = CONTROL_PERIOD_MS / 1000.0f;
  unsigned long tiempoAnterior = millis();
  long conteoAnterior = leerConteo();
  float integral = 0.0f;
  float errorAnterior = 0.0f;

  for (;;) {
    vTaskDelayUntil(&ultimoDespertar, periodo);
    const long conteoActual = leerConteo();
    const float angulo = (conteoActual / CPR) * 360.0f;
    const auto comando = ComunicacionS3::leerComando();
    const float referencia = comando.referencia;
    const float error = referencia - angulo;
    float salida = 0.0f;

    if (comando.habilitado) {
      const float integralPropuesta = integral + error * dt;
      const float derivada = (error - errorAnterior) / dt;

#if defined(CONTROL_MODE_P)
      salida = KP * error;
#elif defined(CONTROL_MODE_PI)
      const float salidaPropuesta = KP * error + KI * integralPropuesta;
      const bool integralEmpujaSaturacion =
          (salidaPropuesta > 255.0f && error > 0.0f) ||
          (salidaPropuesta < -255.0f && error < 0.0f);
      if (!integralEmpujaSaturacion) {
        integral = integralPropuesta;
      }
      salida = KP * error + KI * integral;
#elif defined(CONTROL_MODE_PID)
      const float salidaPropuesta =
          KP * error + KI * integralPropuesta + KD * derivada;
      const bool integralEmpujaSaturacion =
          (salidaPropuesta > 255.0f && error > 0.0f) ||
          (salidaPropuesta < -255.0f && error < 0.0f);
      if (!integralEmpujaSaturacion) {
        integral = integralPropuesta;
      }
      salida = KP * error + KI * integral + KD * derivada;
#endif

      salida = constrain(salida, -255.0f, 255.0f);
      errorAnterior = error;
      moverMotor(salida);
    } else {
      integral = 0.0f;
      errorAnterior = 0.0f;
      detenerMotor();
    }
    ComunicacionS3::actualizarTelemetria(referencia, angulo, error, salida);
    const unsigned long ahora = millis();
    const unsigned long transcurrido = ahora - tiempoAnterior;
    if (transcurrido >= TELEMETRY_PERIOD_MS) {
      const long pulsosNuevos = conteoActual - conteoAnterior;
      const float rpm = fabsf((pulsosNuevos / CPR) * 60000.0f / transcurrido);
      Serial.printf("Ref: %.1f | Angulo: %.2f | RPM: %.2f | Error: %.2f | U: %.1f\\n",
                    referencia, angulo, rpm, error, salida);
      conteoAnterior = conteoActual;
      tiempoAnterior = ahora;
    }
  }
}
}  // namespace

namespace ControlPosicion {
void iniciarHardware() {
  pinMode(R_EN, OUTPUT);
  pinMode(L_EN, OUTPUT);
  digitalWrite(R_EN, LOW);
  digitalWrite(L_EN, LOW);
  ledcSetup(CANAL_RPWM, FRECUENCIA_PWM, RESOLUCION_PWM);
  ledcSetup(CANAL_LPWM, FRECUENCIA_PWM, RESOLUCION_PWM);
  ledcAttachPin(RPWM, CANAL_RPWM);
  ledcAttachPin(LPWM, CANAL_LPWM);
  detenerMotor();
  pinMode(ENC_A, INPUT_PULLUP);
  pinMode(ENC_B, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(ENC_A), leerEncoder, RISING);
}

void iniciarTarea() {
  const BaseType_t resultado = xTaskCreatePinnedToCore(
      tareaControl, "ControlPosicion", 4096, nullptr, 5, nullptr, 1);
  if (resultado != pdPASS) {
    detenerMotor();
    Serial.println("[Control] No se pudo iniciar la tarea; motor detenido.");
  }
}
}
