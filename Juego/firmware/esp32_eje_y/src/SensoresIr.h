#pragma once

#include <stdint.h>

// Matriz de sensores IR, uno por casilla del gato (ver IR_PINES en Config.h).
namespace SensoresIr {
void iniciar();
/// Bit i encendido = el sensor de la casilla i ve la cabeza.
uint16_t leerMascara();
}  // namespace SensoresIr
