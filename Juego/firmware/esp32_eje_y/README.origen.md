# ESP32: motor con control de posición

Este proyecto usa una sola ESP32, un motor con encoder y el driver BTS7960. Abre esta carpeta como proyecto PlatformIO.

- `src/main.cpp`: inicia el control y la comunicación.
- `src/ControlPosicion.cpp`: encoder, controlador P/PI/PID seleccionable y salida al BTS7960.
- `src/ComunicacionPagina.h/.cpp`: Wi-Fi, MQTT, comandos y telemetría.
- `include/Config.h`: pines, ganancia, resolución del encoder y periodos.
- `include/Secrets.h`: credenciales privadas y certificado TLS; se excluye de Git.

## Conexiones

| Señal | GPIO ESP32 |
|---|---|
| Encoder A | 32 |
| Encoder B | 33 |
| BTS7960 RPWM | 25 |
| BTS7960 LPWM | 26 |
| BTS7960 R_EN | 27 |
| BTS7960 L_EN | 14 |
| GND del encoder y del driver | GND común |

Los dos cables del motor van a las salidas para motor del BTS7960. La fuente del motor va a la entrada de potencia del driver; el motor no se alimenta desde la ESP32.

Une las tierras de la ESP32, el encoder, el driver y la fuente del motor. La alimentación lógica del driver y la del encoder deben corresponder a sus módulos.

Las señales A/B que entran a la ESP32 deben ser de **3.3 V**; si tu encoder entrega 5 V, adapta los niveles antes de conectarlas.

## Control

Selecciona P, PI o PID activando un solo `CONTROL_MODE_*` en `include/Config.h`; PID queda activo por defecto. El control usa `error = referencia - angulo` y calcula la salida cada 10 ms; PI y PID incluyen anti-windup. Las ganancias copiadas del PID proporcionado son `KP = 4.5122`, `KI = 21.0020` y `KD = 0.1834`. El ejemplo original usa entrada analógica y DAC para otra planta, por lo que estas ganancias aún deben validarse en el motor con encoder y BTS7960. La salida se limita a -255…255; el driver usa PWM de 5 kHz y 8 bits. La telemetría se envía cada 100 ms.

El encoder cuenta flancos ascendentes de A y determina el sentido leyendo B. Se conservan `PPR = 11` y `REDUCCION = 34`, con un factor de escala acumulado de 4: `CPR = 11 × 34 × 4 = 1496`. Este segundo ajuste duplica el valor anterior de 748, porque se observó que 180° indicados correspondían aproximadamente a 90° físicos. Después de cargar el firmware, comprueba el giro físico para confirmar la escala.

La posición del eje al encender define los 0°. La página manda un ángulo relativo a ese punto. El encoder incremental no conserva una posición absoluta al reiniciar.

Se invirtió el sentido de los ángulos: +90° gira hacia donde antes giraba -90°, y viceversa. Para mantener la corrección del control, se invirtieron juntos el conteo del encoder y el sentido del driver. Una salida positiva usa RPWM y una negativa usa LPWM; el encoder suma cuando B está en HIGH. La escala permanece en 1496 pulsos por vuelta. Este cambio requiere volver a subir el firmware; editar el archivo no modifica la ESP32 conectada.

El motor espera una referencia válida de la página. No alterna referencias ni ejecuta una prueba temporizada. Al recibir paro, perder MQTT o vencer el comando, se apagan los PWM y los enable del driver. Después de un reset espera otro ángulo; el reset no borra el conteo del encoder.

## Comunicación

En `setup()`, el main llama a `ComunicacionPagina::iniciar()`. En cada paso del control, `leerComando()` entrega la referencia y si se permite mover el motor. `actualizarTelemetria()` guarda referencia, ángulo, error y salida; otra tarea los publica por MQTT.

Configura Wi-Fi, broker y credenciales en `include/Secrets.h`. Para el broker local, usa la IP de tu PC, puerto 1883 y `MQTT_USE_TLS=false`. La ESP32 no puede usar `127.0.0.1` para llegar a tu PC. Para un broker con TLS, usa `MQTT_USE_TLS=true` y configura `MQTT_ROOT_CA_PEM`. Sigue `../pagina_esp/README.md` para conectar la página al dispositivo real.

Compila con **PlatformIO: Build** y carga con **PlatformIO: Upload**. El monitor serial usa 115200 baudios y muestra referencia, ángulo, RPM, error y salida.

## Subir el código desde PowerShell

Conecta la ESP32 por USB y ejecuta:

```powershell
Set-Location -LiteralPath 'C:\Users\santi\OneDrive\Documents\Analysis-of-Control-Systems\Proyecto\ESP32'
& 'C:\Users\santi\.platformio\penv\Scripts\platformio.exe' run --target upload
```

Este comando compila y sube el firmware.

## Abrir la terminal de la ESP32

Cuando termine la carga, ejecuta en esa terminal o en otra:

```powershell
Set-Location -LiteralPath 'C:\Users\santi\OneDrive\Documents\Analysis-of-Control-Systems\Proyecto\ESP32'
& 'C:\Users\santi\.platformio\penv\Scripts\platformio.exe' device monitor --baud 115200
```

Pulsa **Ctrl+C** para cerrar el monitor antes de volver a subir código: ambos comandos usan el mismo puerto USB.

Si PlatformIO no detecta el puerto, lista los dispositivos:

```powershell
& 'C:\Users\santi\.platformio\penv\Scripts\platformio.exe' device list
```

Usa el puerto que aparezca, por ejemplo `COM5`: añade `--upload-port COM5` al comando de carga y `--port COM5` al comando del monitor.

## Si la página muestra OFFLINE

El USB permite cargar código y ver el monitor; el estado ONLINE llega por MQTT. La ESP32 debe conectarse a Wi-Fi y al broker, y el backend también debe estar conectado a ese broker.

Para MQTT local, abre Docker Desktop y arranca el broker siguiendo `../pagina_esp/README.md`. El monitor muestra `[WiFi] Reconectando`, los intentos MQTT y `[MQTT] motor-01 conectado` cuando la conexión funciona. Si cambias el firmware o `Secrets.h`, vuelve a subir el código.

Antes de una prueba con carga, verifica que una orden positiva produzca un aumento del ángulo medido. Si el sentido del encoder y el motor no coinciden, el control se moverá en la dirección equivocada.
