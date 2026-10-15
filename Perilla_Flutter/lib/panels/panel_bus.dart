import 'package:flutter/services.dart';

/// Mensajes entre los paneles del Quest.
///
/// Cada panel es una actividad con su propio motor e isolate, así que no
/// comparten memoria: el lado nativo (`PanelBus` en Kotlin) reenvía lo que
/// manda uno a todos los demás. El panel de opciones es el dueño del estado
/// y de la conexión BLE; la perilla solo propone valores.
///
///   perilla  -> opciones  [hello]  acaba de abrirse, pide el estado
///   perilla  -> opciones  [set]    {value, final}
///   opciones -> perilla   [state]  {angle, range, measured, connected}
///   perilla3d -> opciones [knobMode] {mode: '2d'} volver a las ventanas 2D
///   opciones -> nativo    [openKnob] abre o trae al frente la perilla
///   opciones -> nativo    [openKnob3d] abre la perilla 3D (inmersiva)
///   opciones -> nativo    [closeKnob] / [closeKnob3d] cierra esa perilla
///
/// La perilla 3D es Kotlin nativo, no Flutter, pero habla igual: manda
/// `hello` y `set` y recibe `state`.
///   opciones -> nativo    [openCanvas] abre el lienzo (gesto secreto)
class PanelBus {
  PanelBus._();

  static const _channel = MethodChannel('control_angulos/panels');

  static const hello = 'hello';
  static const set = 'set';
  static const state = 'state';
  static const knobMode = 'knobMode';
  static const openKnob = 'openKnob';
  static const openKnob3d = 'openKnob3d';
  static const closeKnob = 'closeKnob';
  static const closeKnob3d = 'closeKnob3d';
  static const openCanvas = 'openCanvas';

  static void listen(void Function(String method, Map args) onMessage) {
    _channel.setMethodCallHandler((call) async {
      onMessage(call.method, (call.arguments as Map?) ?? const {});
    });
  }

  static void send(String method, [Map<String, Object?>? args]) {
    // Si el otro panel está cerrado el mensaje simplemente se pierde.
    _channel.invokeMethod<void>(method, args).ignore();
  }
}
