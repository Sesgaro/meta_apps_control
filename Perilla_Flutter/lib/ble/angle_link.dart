import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';

import 'angle_protocol.dart';

enum LinkStatus { disconnected, connecting, connected }

/// Conexión con la ESP32-S3 y envío de ángulos.
///
/// Al girar la perilla llegan decenas de valores por segundo, más de los que
/// cabe mandar por BLE. En vez de encolarlos todos (el servo iría con retraso
/// creciente) solo se guarda el último: mientras una escritura está en curso,
/// los nuevos valores se pisan entre sí y al terminar se manda el más reciente.
class AngleLink extends ChangeNotifier {
  LinkStatus _status = LinkStatus.disconnected;
  LinkStatus get status => _status;

  BluetoothDevice? _device;
  BluetoothDevice? get device => _device;

  BluetoothCharacteristic? _angleChar;
  StreamSubscription<BluetoothConnectionState>? _connSub;
  StreamSubscription<List<int>>? _stateSub;

  /// Último estado que publicó la ESP (posición medida, etc.). Null si aún
  /// no llega nada o el firmware no tiene la característica de estado.
  MotorState? _motor;
  MotorState? get motor => _motor;

  /// El firmware conectado publica su estado.
  bool _hasState = false;
  bool get hasState => _hasState;

  String? _error;
  String? get error => _error;

  /// Último ángulo que la ESP32 recibió (o al menos que se le escribió).
  double? _lastSent;
  double? get lastSent => _lastSent;

  bool _writing = false;
  ({double degrees, int channel})? _pending;
  int? _pendingMode;

  bool get isConnected => _status == LinkStatus.connected;

  String get deviceName {
    final d = _device;
    if (d == null) return '';
    return d.platformName.isNotEmpty ? d.platformName : d.remoteId.str;
  }

  Future<void> connect(BluetoothDevice device) async {
    await disconnect();
    _device = device;
    _error = null;
    _setStatus(LinkStatus.connecting);

    try {
      // Licencia sin fines de lucro (uso personal/educativo). Si la app se
      // usa comercialmente, flutter_blue_plus exige License.commercial.
      await device.connect(
        license: License.nonprofit,
        timeout: const Duration(seconds: 12),
      );

      _connSub = device.connectionState.listen((s) {
        if (s == BluetoothConnectionState.disconnected && _device == device) {
          _angleChar = null;
          _motor = null;
          _hasState = false;
          _error ??= 'Se perdió la conexión';
          _setStatus(LinkStatus.disconnected);
        }
      });

      final services = await device.discoverServices();
      final service = services
          .where((s) => s.uuid == AngleProtocol.serviceUuid)
          .firstOrNull;
      final char = service?.characteristics
          .where((c) => c.uuid == AngleProtocol.angleCharUuid)
          .firstOrNull;
      if (char == null) {
        throw StateError('El dispositivo no expone el servicio de ángulos');
      }

      _angleChar = char;
      await _subscribeState(service!);
      _setStatus(LinkStatus.connected);
    } catch (e) {
      _error = _describe(e);
      await _teardown();
      _setStatus(LinkStatus.disconnected);
    }
  }

  /// Se suscribe al estado si el firmware lo ofrece; si no, sigue sin él.
  Future<void> _subscribeState(BluetoothService service) async {
    final char = service.characteristics
        .where((c) => c.uuid == AngleProtocol.stateCharUuid)
        .firstOrNull;
    if (char == null) return;
    _stateSub = char.onValueReceived.listen((bytes) {
      final s = AngleProtocol.decodeState(bytes);
      if (s == null) return;
      _motor = s;
      notifyListeners();
    });
    try {
      await char.setNotifyValue(true);
      _hasState = true;
      // Valor inicial sin esperar a la primera notificación.
      if (char.properties.read) await char.read();
    } catch (_) {
      // Sin estado se puede seguir mandando ángulos.
    }
  }

  Future<void> disconnect() async {
    _error = null;
    await _teardown();
    _setStatus(LinkStatus.disconnected);
  }

  Future<void> _teardown() async {
    await _connSub?.cancel();
    _connSub = null;
    await _stateSub?.cancel();
    _stateSub = null;
    _motor = null;
    _hasState = false;
    _angleChar = null;
    _pending = null;
    _pendingMode = null;
    final d = _device;
    if (d != null && d.isConnected) {
      try {
        await d.disconnect();
      } catch (_) {}
    }
  }

  /// Pide mandar [degrees]. Si hay otra escritura en curso, este valor
  /// reemplaza al pendiente en vez de ponerse en cola.
  void send(double degrees, {int channel = 0}) {
    if (!isConnected) return;
    _pending = (degrees: degrees, channel: channel);
    if (!_writing) _drain();
  }

  /// Pide fijar el modo de rango compartido en la ESP. Se escribe antes
  /// que el ángulo pendiente, así el otro dispositivo recibe el modo nuevo
  /// junto con la referencia ya recortada a ese rango.
  void sendMode(int mode) {
    if (!isConnected || !_hasState) return;
    _pendingMode = mode;
    if (!_writing) _drain();
  }

  Future<void> _drain() async {
    _writing = true;
    try {
      while (_pending != null || _pendingMode != null) {
        final char = _angleChar;
        if (char == null) break;
        final mode = _pendingMode;
        if (mode != null) {
          _pendingMode = null;
          await char.write(
            AngleProtocol.encodeMode(mode),
            withoutResponse: char.properties.writeWithoutResponse,
          );
          continue;
        }
        final next = _pending!;
        _pending = null;

        // Sin respuesta: menor latencia, y si un valor intermedio se pierde
        // da igual porque el siguiente lo sustituye.
        final withoutResponse = char.properties.writeWithoutResponse;
        await char.write(
          AngleProtocol.encode(next.degrees, channel: next.channel),
          withoutResponse: withoutResponse,
        );
        _lastSent = next.degrees;
        notifyListeners();
      }
    } catch (e) {
      _error = _describe(e);
      notifyListeners();
    } finally {
      _writing = false;
    }
  }

  void clearError() {
    if (_error == null) return;
    _error = null;
    notifyListeners();
  }

  void _setStatus(LinkStatus s) {
    _status = s;
    notifyListeners();
  }

  static String _describe(Object e) {
    if (e is FlutterBluePlusException) return e.description ?? e.toString();
    if (e is StateError) return e.message;
    return e.toString();
  }

  @override
  void dispose() {
    _teardown();
    super.dispose();
  }
}
