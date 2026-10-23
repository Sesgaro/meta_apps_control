import 'package:flutter/services.dart';

/// Mensajes entre el panel 2D (Flutter) y el tablero 3D del Quest
/// (GameActivity3D, Kotlin nativo).
///
/// Son actividades distintas, así que no comparten memoria: el lado nativo
/// (`PanelBus` en Kotlin) reenvía lo que manda una a la otra. El panel 2D es
/// el dueño de la conexión BLE; el tablero 3D solo propone movimientos.
///
///   3D -> 2D      [hello]     acaba de abrirse, pide el estado
///   3D -> 2D      [move]      {x, y} en mm, mover la cabeza
///   3D -> 2D      [fire]      tirar: lanzar la pelota / soltar la garra
///   3D -> 2D      [newGame]   partida nueva
///   3D -> 2D      [calibrate] botón Calibrar / Cancelar
///   3D -> 2D      [touch]     {step} tocaste la cabeza en ese punto de la calibración
///   3D -> 2D      [pose]      {data} tu pose (PoseFrame) para el otro visor
///   2D -> 3D      [poses]     {data} pose del otro visor
///   2D -> 3D      [game]      estado del juego, ver GameState.toMap()
///   2D -> nativo  [openGame3d] / [closeGame3d] abre o cierra el tablero 3D
///   2D -> nativo  loadPrefs / savePrefs: ficha y modo guardados (enteros)
class PanelBus {
  PanelBus._();

  static const _channel = MethodChannel('control_angulos/panels');

  static const hello = 'hello';
  static const move = 'move';
  static const fire = 'fire';
  static const newGame = 'newGame';
  static const calibrate = 'calibrate';
  static const touch = 'touch';
  static const pose = 'pose';
  static const poses = 'poses';
  static const game = 'game';
  static const openGame3d = 'openGame3d';
  static const closeGame3d = 'closeGame3d';

  static void listen(void Function(String method, Map args) onMessage) {
    _channel.setMethodCallHandler((call) async {
      onMessage(call.method, (call.arguments as Map?) ?? const {});
    });
  }

  static Future<Map<String, Object?>> loadPrefs() async =>
      await _channel.invokeMapMethod<String, Object?>('loadPrefs') ?? {};

  static void savePrefs(Map<String, int> prefs) =>
      _channel.invokeMethod<void>('savePrefs', prefs).ignore();

  static void send(String method, [Map<String, Object?>? args]) {
    // Si el otro panel está cerrado el mensaje simplemente se pierde.
    _channel.invokeMethod<void>(method, args).ignore();
  }
}
