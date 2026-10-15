#include <Arduino.h>
#include "ComunicacionS3.h"
#include "ControlPosicion.h"

void setup() {
  Serial.begin(115200);
  ControlPosicion::iniciarHardware();
  ComunicacionS3::iniciar();
  ControlPosicion::iniciarTarea();
}

void loop() {
  vTaskDelay(pdMS_TO_TICKS(1000));
}
