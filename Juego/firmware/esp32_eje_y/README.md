# ESP32 normal: eje Y + matriz IR

Basado en `Project/ESP32` de Analysis-of-Control-Systems (su README original
está en `README.origen.md`). Cambios:

- `ComunicacionPagina` (Wi-Fi + MQTT) → `ComunicacionS3` (UART con la
  ESP32-S3), con la misma interfaz para el control: `leerComando()` y
  `actualizarTelemetria()`. Sin órdenes 0.5 s, el motor se apaga.
- `SensoresIr`: lee los 9 sensores de la matriz y manda la máscara a la S3.
- Eje lineal: la referencia llega en mm y se convierte a grados del motor con
  `MM_POR_VUELTA` (husillo T8, 8 mm); el PID sigue igual, en grados.

Pines, UART y sensores en `include/Config.h`. Compilar y subir:

```sh
pio run --target upload
pio device monitor -b 115200   # Ref/Ángulo/RPM/Error/U, como antes
```

Protocolo y cableado completos en el README de la raíz del proyecto.
