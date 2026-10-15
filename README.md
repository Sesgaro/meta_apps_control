# meta_apps_control

Apps para controlar motores DC (ESP32 + encoder + driver BTS7960) desde Meta
Quest y teléfonos Android por Bluetooth LE. Hechas en Flutter, con una versión
para teléfono y otra para Meta Quest (paneles 2D sin fondo sobre el
passthrough + escena 3D inmersiva con Meta Spatial SDK).

| Proyecto | Qué hace | Hardware |
|---|---|---|
| [`Perilla_Flutter/`](Perilla_Flutter/README.md) | Perilla para mandar un ángulo a un motor y ver su posición real. | 1 ESP32-S3, 1 motor |
| [`Juego/`](Juego/README.md) | Gato (tic-tac-toe) en una máquina cartesiana X/Y, jugado por dos Quest. | ESP32-S3 + ESP32 normal, 2 motores, matriz IR 3×3 |

`Juego` nació como copia de `Perilla_Flutter`: comparten la base (tema,
conexión BLE, paneles del Quest, Spatial SDK) y el PID del motor, pero son
apps independientes (`com.sesgaro.control_angulos` y `com.sesgaro.gato`) que
pueden instalarse a la vez.

## Perilla_Flutter: control de ángulos

```
 Teléfono / Quest ── BLE ──► ESP32-S3 ──► BTS7960 ──► motor + encoder
```

- Perilla de ángulo con tres modos: 0–180°, 0–360° y ±90° (en este último gira
  1:1). Además, valor exacto, atajos y ajuste fino.
- La ESP publica posición, referencia, rpm y PWM (servidor GATT). La perilla
  marca dónde está realmente el motor y sigue la referencia, así varios
  dispositivos conectados a la vez (hasta 3) ven y mueven lo mismo, incluido
  el modo de rango.
- En el Quest: opciones y perilla en ventanas separadas, o **perilla 3D**
  inmersiva que se gira con la mano como una perilla de puerta.
- Firmware: encoder por PCNT en cuadratura ×4 (0.24°) y PID calculado por
  asignación de polos sobre el motor identificado (kp 7.6, ki 51, kd 0.135,
  con compensación de fricción): 0 % de sobrepaso y asentamiento ≤ 0.25 s.

## Juego: gato cartesiano

```
 Quest 1 ─┐ BLE                       UART
 Quest 2 ─┴──► ESP32-S3 ◄──────────────────► ESP32 normal
               · árbitro del juego            · eje Y (husillo T8)
               · eje X (banda / cremallera)   · matriz IR 3×3
```

- Cada jugador, en su turno de 20 s, arrastra la cabeza de la máquina sobre un
  tablero virtual (2D o 3D en el Quest); los motores X/Y la siguen.
- Al acabarse el tiempo los motores se bloquean y la matriz IR dice sobre qué
  casilla quedó la cabeza: casilla libre = ficha; ninguna, varias u ocupada =
  turno perdido. Después controla el otro visor.
- Los ejes se controlan en milímetros (40 mm por vuelta en X, 8 mm en Y); el
  PID sigue en grados del motor.
- La ESP32 normal usa el firmware de
  [Analysis-of-Control-Systems](https://github.com/santi-bnt/Analysis-of-Control-Systems)
  con Wi-Fi/MQTT cambiado por UART.

## Requisitos

- Flutter 3.47+ (Dart 3.13) y Android SDK.
- Arduino IDE 2 / arduino-cli con el core **esp32 3.x** (ESP32-S3).
- PlatformIO (ESP32 normal del juego).
- Meta Quest 3 / 3S en modo desarrollador, o teléfono Android 12+.

## Compilar e instalar

Cada app tiene dos versiones (flavors). Sin `--flavor` se compila la de
teléfono.

```sh
cd Perilla_Flutter   # o Juego
flutter build apk --release --flavor phone   # teléfono
flutter build apk --release --flavor quest   # Meta Quest (incluye Spatial SDK, ~105 MB)
adb install -r build/app/outputs/flutter-apk/app-quest-release.apk
```

Los firmwares, el cableado, los protocolos y lo que hay que calibrar están en
el README de cada proyecto.

## Notas del Meta Quest

- **La escena 3D oculta las ventanas 2D** (limitación de Horizon OS). El panel
  2D sigue conectado en segundo plano: conecta primero en 2D y vuelve con el
  botón **Volver a 2D** de la escena.
- La primera vez pide los permisos de **Dispositivos cercanos** (BLE) y de
  **seguimiento de manos**. La perilla 3D y el tablero 3D se usan solo con las
  manos.
- Si el visor tiene varias cuentas, cada una abre su propia copia de la app y
  ocupa su propia conexión BLE.
- Para ver lo que ve el jugador con scrcpy (en Linux con Wayland):

  ```sh
  scrcpy --no-audio --hwdec=disabled --crop=1832:1920:0:0 --max-size=1600
  ```

  `--hwdec=disabled` evita que la ventana salga vacía por la decodificación
  VA-API; `--crop` muestra un solo ojo.
