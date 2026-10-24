# Tic-Cat-Toe (gato cartesiano)

Gato (tic-tac-toe) jugado con una máquina cartesiana X/Y desde dos Meta Quest.
Cada jugador, en su turno, arrastra la cabeza de la máquina sobre un tablero
virtual; al acabarse el tiempo, una matriz de LDR dice sobre qué
casilla quedó la cabeza. Si le atinó a una casilla libre, se pone su ficha; si
no, pierde el turno. Después de su turno los motores quedan bloqueados para él
y el otro visor toma el control.

Parte del proyecto `App` (control de ángulos por BLE); el firmware del eje X
(`firmware/esp32_eje_x/`) viene de [Analysis-of-Control-Systems](https://github.com/santi-bnt/Analysis-of-Control-Systems)
(copia de referencia en `referencia/`), con Wi-Fi/MQTT cambiado por UART.

```
 Quest 1 ─┐ BLE                       UART (TX/RX/GND)
 Quest 2 ─┴──► ESP32-S3 ◄─────────────────────────► ESP32 normal
               · árbitro: turnos, reloj, tablero     · motor eje X (cremallera)
               · motor eje Y (husillo T8)            · posición de X
               · matriz LDR 3×3 (fija, analógica)
               · BLE para los visores
```

## Partes

| Carpeta | Qué es |
|---|---|
| `firmware/esp32s3_gato/` | ESP32-S3 (Arduino IDE / arduino-cli). `gato.h` = reglas sin Arduino. |
| `firmware/esp32_eje_x/` | ESP32 normal (PlatformIO). Eje X + UART. |
| `firmware/pruebas/` | Pruebas de `gato.h` en la PC. |
| `lib/` | App Flutter: panel 2D (conexión, ficha, estado, tablero arrastrable). |
| `android/app/src/quest/.../GameActivity3D.kt` | Tablero 3D inmersivo (Meta Spatial SDK). |

## Cómo se juega

1. Enciende las dos ESP con la cabeza en la **esquina mínima** (X e Y): los
   encoders son incrementales y esa posición es 0 mm.
2. En cada Quest abre **Tic-Cat-Toe**, toca **Conectar** →
   `ESP32S3-Gato`, y elige **Jugador X** o **Jugador O** (uno cada visor).
3. **Nuevo juego**. Quien empieza se alterna entre partidas.
4. Elige el **Modo de juego** en el panel. El tablero 3D se abre solo al
   conectar (o con **Abrir tablero 3D**): una mesa frente a ti, que en tu turno
   se ve rosa. Se juega solo con las manos:
   - **Resortera**: agarra la pelota roja, jálala hacia ti y suéltala. Mientras
     apuntas, unos puntos marcan la trayectoria y la cabeza de la máquina va a
     donde caería. Al soltar se tira el turno: si la matriz LDR ve la cabeza en
     una casilla libre la pelota se cuela por un hueco; si no, rebota.
   - **Grúa**: inclina el joystick (izquierda) y la cabeza avanza hacia allá
     mientras lo tengas inclinado; al soltarlo se detiene. Aprieta el botón
     rojo (derecha) para soltar la garra y tirar.
   Cada visor elige su modo. Los jugadores quedan frente a frente con el
   tablero en medio (el jugador O del lado de Y máxima, con la mesa girada
   media vuelta), y cada uno ve al otro con su resortera o su grúa enfrente.
   Los modelos fuente están en `modelos/` (exportados de Blender) y se
   convierten a `android/app/src/quest/assets/models/` con:
   - `python3 tool/modelos_a_glb.py`: fichas (`cross2.glb`, `circle2.glb`) y
     resortera (`slingshot3.glb`); de cada GLB toma solo su objeto.
   - `python3 tool/pata_a_glb.py`: la pata de gato (`pata_modelo.glb`),
     riggeada con 2 huesos y horneada en 4 cuadros de abierta a cerrada, gris
     (X) y naranja (O). En cada mano llevas una, del color de tu ficha, en vez
     de la mano transparente; se cierra con tu mano (rastreo de cuerpo: la app
     pide ese permiso).
   - `python3 tool/cabeza_a_glb.py`: al otro jugador lo ves como un gato (gris
     si es X, naranja si es O) con la cabeza `cabeza_gato.glb` y la cara de
     `sprites.png` según cómo le va (normal, falló su tiro, perdió, acertó o
     ganó), más sus patas.
   **Dos visores**: para que los dos vean el tablero sobre la máquina real,
   con la partida en espera o terminada los dos aprietan **Calibrar** (en el
   letrero o el botón cian junto al tablero) a la vez (menos de 2 s; si el
   otro visor no está conectado basta uno). El tablero se pone verde, la S3
   lleva la cabeza al centro de la casilla 0 y una pelota marca dónde está:
   cada jugador toca la cabeza real con la punta del índice (apuntando) y se
   queda quieto 0.8 s. Cuando los dos tocaron, la cabeza va a la casilla 8 y
   se repite; con los dos toques cada visor acomoda su mesa (giro, escala y
   altura). En la simulación la pelota flota donde estaría la cabeza.
   `HEAD_TOUCH_M` (GameActivity3D.kt) es la altura de la parte de arriba de la
   cabeza real sobre la mesa: mídela. Cada visor manda su cabeza y manos por
   la S3 y ve al otro como gato; en la simulación ves tu propio reflejo.
   En la mesa: barra de tiempo del turno al fondo (roja en los últimos 5 s),
   disco amarillo donde caerá la pelota, línea dorada y confeti al ganar, y
   sonidos (liga, hueco, rebote, garra, cuenta regresiva). El marcador de la
   sesión sale en el letrero y en el panel. La calibración se conserva al
   volver del 2D mientras no cierres la app; ficha y modo se recuerdan.
   Si la conexión BLE se cae sola, la app reintenta cada 3 s (hasta 10 veces).
5. Al tirar, o si se acaba el tiempo (20 s), la ESP espera a que la cabeza se detenga y lee
   los sensores: baldosa verde = ficha puesta, roja = falló u ocupada; azul =
   la casilla que ve la matriz LDR en ese momento.
6. **Volver a 2D** regresa al panel (la escena 3D oculta las ventanas 2D; el
   panel sigue conectado en segundo plano). Al abrir el 3D el panel se
   minimiza solo, porque mientras está al frente las manos no llegan al 3D.

En el Quest solo se juega en 3D. El teléfono es espectador: ve el tablero 2D
y la partida, pero no mueve la cabeza ni empieza partidas. **Probar sin ESP**
(en el Quest) simula la máquina para ver el juego sin hardware; ahí un solo
visor juega con las dos fichas.

## Cableado

**ESP32-S3** (eje Y, motor y pines como en `App`):

| Señal | GPIO |
|---|---|
| Encoder A / B | 21 / 47 |
| BTS7960 RPWM / LPWM | 5 / 4 |
| BTS7960 R_EN / L_EN | 12 / 14 |
| UART TX → RX de la ESP32 normal | 17 |
| UART RX ← TX de la ESP32 normal | 18 |
| LDR (salida AO), casillas 0…8 | 1, 2, 3, 6, 7, 8, 9, 10, 11 |

**ESP32 normal** (eje X, pines del proyecto original):

| Señal | GPIO |
|---|---|
| Encoder A / B | 32 / 33 |
| BTS7960 RPWM / LPWM | 25 / 26 |
| BTS7960 R_EN / L_EN | 27 / 14 |
| UART RX ← TX de la S3 | 16 |
| UART TX → RX de la S3 | 17 |

- **GND común** entre las dos ESP, los drivers, los encoders y los sensores.
- Casilla = fila × 3 + columna; fila 0 = Y mínima, columna 0 = X mínima.
- Cada LDR se lee en analógico (0–4095) y cuenta como "cabeza encima" al
  pasar `LDR_UMBRAL` (sombra = lectura alta; `LDR_SOMBRA` lo invierte). La S3
  imprime las 9 lecturas por el monitor serie cada 0.5 s para ajustarlo. El
  potenciómetro y la salida DO del módulo no se usan.
- Módulos LDR alimentados a **3.3 V** (no 5 V: AO entra directo al ADC).
  Encoders también a 3.3 V.

## Qué calibrar

| Constante | Dónde | Valor actual |
|---|---|---|
| `MM_POR_VUELTA_Y` | `esp32s3_gato.ino` | 8 mm (husillo T8 del eje Z de la B1) |
| `MM_POR_VUELTA` | `esp32_eje_x/include/Config.h` | 40 mm (GT2, polea 20 dientes de la BIQU B1) |
| `X_MIN/X_MAX`, `Y_MIN/Y_MAX` | S3 (y `MIN/MAX_POSITION_MM` en la normal) | 0–180 mm |
| `X_CASILLA0`, `X_PASO`, `Y_CASILLA0`, `Y_PASO` | S3 | centro 30 mm, paso 60 mm |
| `turnoMs`, `quietoMs`, `resolverMaxMs` | S3 (`juego`) | 20 s, 0.3 s, 12 s |
| `TOLERANCIA_MM` | S3 | 2 mm |
| `LDR_PINES`, `LDR_UMBRAL`, `LDR_SOMBRA` | S3 | ver arriba, 2000, sombra |

La geometría viaja en el estado BLE, así que la app (2D y 3D) se ajusta sola:
solo hay que cambiarla en la S3 (y el rango en la normal).

El control de cada eje sigue en grados del motor (las ganancias PID valen
igual) y solo la frontera convierte mm ↔ grados. El PID del eje Y (S3) es el
ajustado en `App` (kp 7.6, ki 51, kd 0.135) y el del eje X (normal) el del repo
original (kp 4.51, ki 21.0, kd 0.18): conviene verificarlos con la carga de
cada carro. El eje Y (husillo, 8 mm/vuelta) avanza ~17 mm/s, por eso el turno es de 20 s.

## Protocolos

**BLE** (servicio `7a1d1000-…`, en `lib/ble/game_protocol.dart` y la S3):

- Comandos (`7a1d1001-…`, escritura sin respuesta):
  `C0 jugador xLo xHi yLo yHi` (int16 en décimas de mm; solo cuenta en el
  turno de ese jugador), `C1 01` (partida nueva), `C2 jugador` (tirar:
  termina el turno ya y se leen las LDR), `C3 jugador` (botón Calibrar /
  Cancelar) y `C4 jugador paso` (ese visor ya tocó la cabeza en el punto).
- Poses, `7a1d1003` (escritura sin respuesta + notificación): `D0 jugador`
  + 28 bytes con cabeza, manos y pelota de un visor en mm del tablero (ver
  `lib/ble/pose_frame.dart`). La S3 no las interpreta: guarda la última de
  cada jugador y la reenvía a todos a 20 Hz.
- Estado (`7a1d1002-…`, lectura + notificación cada 100 ms, 47 bytes): fase,
  turno, tiempo restante, tablero, ganador, último tiro, posición y referencia
  de X/Y, casilla de la matriz LDR, enlace UART, la geometría del tablero y
  la calibración (punto 0-2, quién calibra y quién ya tocó).

**UART** (115200 8N1, líneas de texto con checksum XOR estilo NMEA):

- S3 → normal, cada 50 ms: `$R,<referencia X mm>,<habilitado>*HH`
- normal → S3, cada 50 ms: `$T,<posición mm>,<referencia mm>,<pwm>*HH`

Si la normal deja de recibir líneas 0.5 s, apaga su motor; si la S3 deja de
recibir telemetría 0.5 s, marca el enlace caído (aviso en la app): los tiros
ya no esperan a que X llegue, se resuelven al agotar `resolverMaxMs` con lo
que vean las LDR.

## Compilar y subir

```sh
# Reglas del juego (PC)
cd firmware/pruebas && g++ -std=c++17 -Wall -I../esp32s3_gato test_gato.cpp -o test_gato && ./test_gato

# ESP32-S3 (Arduino IDE, placa "ESP32S3 Dev Module", core esp32 3.x)
arduino-cli compile --fqbn esp32:esp32:esp32s3 firmware/esp32s3_gato
arduino-cli upload -p /dev/ttyACM0 --fqbn esp32:esp32:esp32s3 firmware/esp32s3_gato

# Prueba de dos visores sin máquina: la S3 simula los ejes y la matriz LDR
# (árbitro, BLE y poses de verdad). Para volver al normal, sube sin el flag.
arduino-cli compile --upload -p /dev/ttyACM0 --fqbn esp32:esp32:esp32s3 \
  --build-property "compiler.cpp.extra_flags=-DSIMULAR=1" firmware/esp32s3_gato

# ESP32 normal (PlatformIO)
cd firmware/esp32_eje_x && pio run --target upload

# App
flutter build apk --release --flavor quest   # Meta Quest
flutter build apk --release --flavor phone   # teléfono
```

La app se llama `com.sesgaro.gato` (`.quest` en el visor), así convive con la
app de ángulos. La ESP32-S3 acepta hasta 3 conexiones BLE: los dos Quest y uno
más.
