#pragma once

#include <Arduino.h>

// ESP32 normal del gato: mueve el eje Y de la máquina cartesiana y lee la
// matriz de sensores IR. Recibe la referencia de la ESP32-S3 (que hace BLE y
// lleva el juego) por UART en vez de Wi-Fi/MQTT.
//
// El eje Y es un husillo (el eje Z de una BIQU B1: T8, 8 mm por vuelta). El
// PID sigue trabajando en grados del motor, como en el proyecto original (sus
// ganancias valen igual); solo la frontera cambia: la referencia llega en mm
// y se convierte a grados, y la posición se reporta en mm.

// --- Motor del eje Y (igual que el proyecto original) ---------------------
constexpr int ENC_A = 32;
constexpr int ENC_B = 33;
constexpr int RPWM = 25;
constexpr int LPWM = 26;
constexpr int R_EN = 27;
constexpr int L_EN = 14;
constexpr int CANAL_RPWM = 0;
constexpr int CANAL_LPWM = 1;
constexpr int FRECUENCIA_PWM = 5000;
constexpr int RESOLUCION_PWM = 8;
constexpr unsigned long CONTROL_PERIOD_MS = 10;
constexpr unsigned long TELEMETRY_PERIOD_MS = 100;   // monitor serie USB

// --- UART con la ESP32-S3 -------------------------------------------------
// Serial2. Cruzado: TX de esta placa -> RX de la S3 y viceversa, GND común.
constexpr int UART_RX = 16;
constexpr int UART_TX = 17;
constexpr unsigned long UART_BAUD = 115200;
// Cada cuánto se le manda a la S3 la posición y los sensores.
constexpr unsigned long UART_TELEMETRY_MS = 50;
// Sin órdenes de la S3 en este tiempo se apaga el motor (la S3 manda a 20 Hz).
constexpr unsigned long STALE_COMMAND_TIMEOUT_MS = 500;

// --- Matriz IR (una casilla del gato por sensor) ---------------------------
// Índice = fila * 3 + columna; fila 0 = Y mínima, columna 0 = X mínima.
// 34 y 35 son solo entrada y no tienen pull-up interno: el módulo IR debe
// dar su propio nivel (la mayoría lo hace) o hay que poner una resistencia.
constexpr int IR_PINES[9] = {4, 13, 18, 19, 21, 22, 23, 34, 35};
// Nivel que da el sensor cuando ve la cabeza. Los módulos IR comunes
// (receptor/obstáculo) ponen la salida en LOW al detectar.
constexpr int IR_ACTIVO = LOW;

// Selecciona un solo controlador: deja su define activo y comenta los otros.
// #define CONTROL_MODE_P
// #define CONTROL_MODE_PI
#define CONTROL_MODE_PID

#if (defined(CONTROL_MODE_P) + defined(CONTROL_MODE_PI) + defined(CONTROL_MODE_PID)) != 1
#error "Activa exactamente un modo: CONTROL_MODE_P, CONTROL_MODE_PI o CONTROL_MODE_PID"
#endif

// Gains copied from the supplied practice PID; validate them on the motor.
constexpr float KP = 4.5122f;
constexpr float KI = 21.0020f;
constexpr float KD = 0.1834f;
constexpr float PPR = 11.0f;
constexpr float REDUCCION = 34.0f;
// Se cuenta un flanco RISING de A, igual que en el código del motor.
// Segundo ajuste: 180 grados indicados correspondían a unos 90 grados reales.
constexpr float CPR = PPR * REDUCCION * 4.0f;  // 1496 pulsos por vuelta

// --- Eje lineal -------------------------------------------------------------
// Avance del carro por vuelta del eje de salida del motor (paso × entradas
// del husillo). Si hay otra reducción entre el motor y el husillo, inclúyela.
constexpr float MM_POR_VUELTA = 8.0f;
// La posición al encender es 0 mm (el encoder es incremental): enciende con
// el carro en el extremo mínimo. Fuera de este rango no se acepta referencia.
constexpr float MIN_POSITION_MM = 0.0f;
constexpr float MAX_POSITION_MM = 180.0f;

constexpr float gradosDeMm(float mm) { return mm * 360.0f / MM_POR_VUELTA; }
constexpr float mmDeGrados(float grados) { return grados * MM_POR_VUELTA / 360.0f; }
