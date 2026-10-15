# Control de ángulos

App Flutter (Android y Meta Quest 3) que manda ángulos por BLE a una ESP32-S3, con perilla
rotativa, valor numérico, atajos y ajuste fino. Diseño Material 3 Expressive
(tema compartido con ARDO_CEL_LOCAL). Rosa por defecto; en Android 12+
usa la paleta del fondo de pantalla.

## Partes

- `lib/ble/angle_protocol.dart`: UUIDs y trama. **Debe coincidir con el firmware.**
- `lib/ble/angle_link.dart`: conexión y envío (solo el último valor, sin cola).
- `lib/ui/widgets/rotary_knob.dart`: perilla (forma de galleta + pista ondulada).
  Se gira arrastrando o con la rueda del ratón / joystick del mando.
- `firmware/esp32s3_receptor_angulos/`: sketch Arduino de recepción.

## Protocolo

Característica `7a1d0001-5e2f-4b8a-9c3d-2f1e0a6b4c11` (servicio `7a1d0000-…`),
trama de 4 bytes: `A5 canal lo hi`, con el ángulo en décimas de grado como
int16 little-endian.

Estado (servidor GATT de la ESP): característica `7a1d0002-…`, lectura +
notificación cada 100 ms, trama de 15 bytes `A6 canal pos(int32) ref(int32)
rpm(int16) pwm(int16) modo`, posición/referencia/rpm en décimas. La app muestra la
posición real en "Lectura de la ESP" y como marcador terciario en la perilla.
La ESP sigue anunciándose tras cada conexión: admite hasta 3 clientes a la vez
(p. ej. teléfono y Quest), y todos reciben el estado.

Modo de rango compartido: la app manda `A7 modo` (índice de `AngleRange`) al
cambiarlo y la ESP lo reparte en el byte 14 del estado (0xFF = sin definir;
la primera app que se conecta le da el suyo). Así todos los dispositivos
muestran el mismo rango y la misma referencia.

## Uso

1. Arduino IDE, placa "ESP32S3 Dev Module" (core esp32 3.x), subir el sketch.
2. `flutter run` con el teléfono conectado.
3. Conectar → elegir `ESP32S3-Angulos` → girar la perilla. La tarjeta
   "Lectura de la ESP" muestra la posición real del motor.

## Firmware

Solo BLE y el control del motor: no usa el puerto serie. El encoder se lee
con el PCNT del S3 en cuadratura x4 (1496 cuentas/vuelta, 0.24°) con filtro
de glitches, y el PID (kp 7.6, ki 51, kd 0.135, con compensación de fricción,
banda muerta de 0.5° e integración solo en los últimos 5°) corre cada 10 ms.
El cálculo de las ganancias está comentado en el sketch.

## Rangos

| Modo    | Valor      | Recorrido de la perilla |
|---------|------------|-------------------------|
| 0–180°  | 0 a 180    | 270° con topes          |
| 0–360°  | 0 a 360    | vuelta completa         |
| ±90°    | -90 a 90   | 180° con topes, 1:1 (0° arriba, -90 izquierda, +90 derecha) |

## Versiones (flavors)

- `phone` (por defecto): la app de teléfono de siempre. `flutter run` /
  `flutter build apk` sin más.
- `quest`: panel 2D para Meta Quest 3/3S (Horizon OS), id
  `com.sesgaro.control_angulos.quest`, se instala junto a la de teléfono.
  Solo cambia el manifiesto (`android/app/src/quest/AndroidManifest.xml`):
  dos paneles independientes y sin fondo (flotan sobre el passthrough): las
  opciones (`MainActivity`) y la perilla (`KnobActivity`, entrada
  `knobMain`). Cada panel tiene su propio motor de Flutter; el de opciones
  es el dueño del estado y del BLE, y se comunican por `lib/panels/panel_bus.dart`
  (reenviado por `PanelBus` en Kotlin). "Mostrar perilla" la reabre si se cerró.
  Perilla 2D o 3D (selector en Ajustes): la 3D es una actividad inmersiva
  (`KnobActivity3D`, Meta Spatial SDK 0.14, solo en el flavor quest) con una
  perilla de puerta sobre el passthrough. Se agarra pellizcando cerca de ella
  y se gira la muñeca; solo manos. Habla con el panel de opciones por el mismo
  `PanelBus` (nativo). La escena inmersiva oculta las ventanas 2D (el panel
  de opciones sigue conectado en segundo plano): conectar antes en 2D, y
  volver con el botón "Volver a 2D" bajo la perilla. El letrero dice "sin
  conexión" si el panel de opciones no está conectado a la ESP.
  Gesto secreto: 5 toques rápidos (< 2 s) sobre el título "Control de ángulos"
  abren un lienzo de dibujo en un tercer panel (`CanvasActivity`, entrada
  `canvasMain`; no exportada, solo se abre desde la app).
  En el teléfono, en pantallas anchas (≥ 840 dp) la interfaz pasa a dos columnas.

  ```sh
  flutter build apk --flavor quest --release
  adb install build/app/outputs/flutter-apk/app-quest-release.apk
  ```

  El visor tiene que estar en modo desarrollador. La app aparece en
  Biblioteca → Orígenes desconocidos. Con el mando: apunta y mantén el gatillo
  para arrastrar la perilla, o usa el joystick sobre ella.
