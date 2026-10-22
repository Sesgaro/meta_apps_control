# ESP32 normal: eje Y

Basado en `Project/ESP32` de Analysis-of-Control-Systems (su README original
está en `README.origen.md`). Cambios:

- `ComunicacionPagina` (Wi-Fi + MQTT) → `ComunicacionS3` (UART con la
  ESP32-S3), con la misma interfaz para el control: `leerComando()` y
  `actualizarTelemetria()`. Sin órdenes 0.5 s, el motor se apaga.
- Los nueve LDR se leen por AO directamente en la ESP32-S3.
- Eje lineal: la referencia llega en mm y se convierte a grados del motor con
  `MM_POR_VUELTA` (piñón-cremallera / GT2 20 dientes, 40 mm); el PID sigue
  igual, en grados.

Pines del motor y UART en `include/Config.h`. Compilar y subir:

```sh
pio run --target upload
pio device monitor -b 115200   # Ref/Ángulo/RPM/Error/U, como antes
```

Protocolo y cableado completos en el README de la raíz del proyecto.
