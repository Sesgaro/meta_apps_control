# Gato cartesiano

Gato (tic-tac-toe) jugado con una máquina cartesiana X/Y desde dos Meta Quest.
Cada jugador, en su turno, arrastra la cabeza de la máquina sobre un tablero
virtual; al acabarse el tiempo, una matriz de sensores IR dice sobre qué
casilla quedó la cabeza. Si le atinó a una casilla libre, se pone su ficha; si
no, pierde el turno. Después de su turno los motores quedan bloqueados para él
y el otro visor toma el control.

Parte del proyecto `App` (control de ángulos por BLE); el firmware del eje Y
viene de [Analysis-of-Control-Systems](https://github.com/santi-bnt/Analysis-of-Control-Systems)
(copia de referencia en `referencia/`), con Wi-Fi/MQTT cambiado por UART.

```
 Quest 1 ─┐ BLE                       UART (TX/RX/GND)
 Quest 2 ─┴──► ESP32-S3 ◄─────────────────────────► ESP32 normal
               · árbitro: turnos, reloj, tablero     · motor eje Y (husillo T8)
               · motor eje X (banda/cremallera)      · matriz IR 3×3
               · BLE para los visores                · posición + qué sensor ve la cabeza
```

## Partes

| Carpeta | Qué es |
|---|---|
| `firmware/esp32s3_gato/` | ESP32-S3 (Arduino IDE / arduino-cli). `gato.h` = reglas sin Arduino. |
| `firmware/esp32_eje_y/` | ESP32 normal (PlatformIO). Eje Y + sensores IR + UART. |
| `firmware/pruebas/` | Pruebas de `gato.h` en la PC. |
| `lib/` | App Flutter: panel 2D (conexión, ficha, estado, tablero arrastrable). |
| `android/app/src/quest/.../GameActivity3D.kt` | Tablero 3D inmersivo (Meta Spatial SDK). |

## Cómo se juega

1. Enciende las dos ESP con la cabeza en la **esquina mínima** (X e Y): los
   encoders son incrementales y esa posición es 0 mm.
2. En cada Quest abre **Gato cartesiano**, toca **Conectar** →
   `ESP32S3-Gato`, y elige **Jugador X** o **Jugador O** (uno cada visor).
3. **Nuevo juego**. Quien empieza se alterna entre partidas.
4. **Abrir tablero 3D**: aparece una mesa con el tablero frente a ti. En tu
   turno la mesa se ve rosa: pellizca la cabeza naranja y arrástrala; los
   motores la siguen. Cuando no es tu turno, la cabeza no se deja agarrar.
5. Al acabar el tiempo (20 s) la ESP espera a que la cabeza se detenga y lee
   los sensores: baldosa verde = ficha puesta, roja = falló u ocupada; azul =
   la casilla que ve la matriz IR en ese momento.
6. **Volver a 2D** regresa al panel (la escena 3D oculta las ventanas 2D; el
   panel sigue conectado en segundo plano).

El panel 2D también sirve para jugar (arrastrando sobre el tablero plano), así
que funciona en el teléfono (`flutter run`) y para probar sin entrar al 3D.

## Cableado

**ESP32-S3** (eje X, igual que en `App`):

| Señal | GPIO |
|---|---|
| Encoder A / B | 21 / 47 |
| BTS7960 RPWM / LPWM | 5 / 4 |
| BTS7960 R_EN / L_EN | 12 / 14 |
| UART TX → RX de la ESP32 normal | 17 |
| UART RX ← TX de la ESP32 normal | 18 |

**ESP32 normal** (eje Y, pines del proyecto original):

| Señal | GPIO |
|---|---|
| Encoder A / B | 32 / 33 |
| BTS7960 RPWM / LPWM | 25 / 26 |
| BTS7960 R_EN / L_EN | 27 / 14 |
| UART RX ← TX de la S3 | 16 |
| UART TX → RX de la S3 | 17 |
| Sensores IR, casillas 0…8 | 4, 13, 18, 19, 21, 22, 23, 34, 35 |

- **GND común** entre las dos ESP, los drivers, los encoders y los sensores.
- Casilla = fila × 3 + columna; fila 0 = Y mínima, columna 0 = X mínima.
- Los sensores se consideran activos en **LOW** (`IR_ACTIVO`). 34 y 35 no
  tienen pull-up interno: el módulo debe dar su nivel o lleva resistencia.
- Señales de encoder y sensores a **3.3 V**.

## Qué calibrar

| Constante | Dónde | Valor actual |
|---|---|---|
| `MM_POR_VUELTA_X` | `esp32s3_gato.ino` | 40 mm (GT2, polea 20 dientes de la BIQU B1) |
| `MM_POR_VUELTA` | `esp32_eje_y/include/Config.h` | 8 mm (husillo T8 del eje Z de la B1) |
| `X_MIN/X_MAX`, `Y_MIN/Y_MAX` | S3 (y `MIN/MAX_POSITION_MM` en la normal) | 0–180 mm |
| `X_CASILLA0`, `X_PASO`, `Y_CASILLA0`, `Y_PASO` | S3 | centro 30 mm, paso 60 mm |
| `turnoMs`, `quietoMs`, `resolverMaxMs` | S3 (`juego`) | 20 s, 0.3 s, 4 s |
| `TOLERANCIA_MM` | S3 | 2 mm |
| `IR_PINES`, `IR_ACTIVO` | normal, `Config.h` | ver arriba |

La geometría viaja en el estado BLE, así que la app (2D y 3D) se ajusta sola:
solo hay que cambiarla en la S3 (y el rango en la normal).

El control de cada eje sigue en grados del motor (las ganancias PID valen
igual) y solo la frontera convierte mm ↔ grados. El PID del eje X es el
ajustado en `App` (kp 7.6, ki 51, kd 0.135) y el del eje Y el del repo
original (kp 4.51, ki 21.0, kd 0.18): conviene verificarlos con la carga de
cada carro. El eje Y (8 mm/vuelta) avanza ~17 mm/s, por eso el turno es de 20 s.

## Protocolos

**BLE** (servicio `7a1d1000-…`, en `lib/ble/game_protocol.dart` y la S3):

- Comandos (`7a1d1001-…`, escritura sin respuesta):
  `C0 jugador xLo xHi yLo yHi` (int16 en décimas de mm; solo cuenta en el
  turno de ese jugador) y `C1 01` (partida nueva).
- Estado (`7a1d1002-…`, lectura + notificación cada 100 ms, 45 bytes): fase,
  turno, tiempo restante, tablero, ganador, último tiro, posición y referencia
  de X/Y, casilla IR, enlace UART y la geometría del tablero.

**UART** (115200 8N1, líneas de texto con checksum XOR estilo NMEA):

- S3 → normal, cada 50 ms: `$R,<referencia Y mm>,<habilitado>*HH`
- normal → S3, cada 50 ms: `$T,<posición mm>,<referencia mm>,<pwm>,<máscara IR>*HH`

Si la normal deja de recibir líneas 0.5 s, apaga su motor; si la S3 deja de
recibir telemetría 0.5 s, marca el enlace caído (aviso en la app) y no puede
resolver tiros (cuentan como fallo).

## Compilar y subir

```sh
# Reglas del juego (PC)
cd firmware/pruebas && g++ -std=c++17 -Wall -I../esp32s3_gato test_gato.cpp -o test_gato && ./test_gato

# ESP32-S3 (Arduino IDE, placa "ESP32S3 Dev Module", core esp32 3.x)
arduino-cli compile --fqbn esp32:esp32:esp32s3 firmware/esp32s3_gato
arduino-cli upload -p /dev/ttyACM0 --fqbn esp32:esp32:esp32s3 firmware/esp32s3_gato

# ESP32 normal (PlatformIO)
cd firmware/esp32_eje_y && pio run --target upload

# App
flutter build apk --release --flavor quest   # Meta Quest
flutter build apk --release --flavor phone   # teléfono
```

La app se llama `com.sesgaro.gato` (`.quest` en el visor), así convive con la
app de ángulos. La ESP32-S3 acepta hasta 3 conexiones BLE: los dos Quest y uno
más.
