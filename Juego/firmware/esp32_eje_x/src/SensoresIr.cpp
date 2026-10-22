#include "SensoresIr.h"
#include "Config.h"

namespace SensoresIr {

void iniciar() {
  for (int pin : IR_PINES) {
    // 34-39 son solo entrada y no tienen pull-up; el resto sí.
    pinMode(pin, pin >= 34 ? INPUT : INPUT_PULLUP);
  }
}

uint16_t leerMascara() {
  uint16_t mascara = 0;
  for (int i = 0; i < 9; i++) {
    if (digitalRead(IR_PINES[i]) == IR_ACTIVO) mascara |= 1u << i;
  }
  return mascara;
}

}  // namespace SensoresIr
