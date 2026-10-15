import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';

import 'game_protocol.dart';

enum LinkStatus { disconnected, connecting, connected }

/// Conexión con la ESP32-S3 del gato: recibe el estado del juego y manda los
/// movimientos de la cabeza.
///
/// Al arrastrar la cabeza llegan decenas de posiciones por segundo, más de
/// las que cabe mandar por BLE. Solo se guarda la última: mientras una
/// escritura está en curso las nuevas se pisan entre sí y al terminar se
/// manda la más reciente.
class GameLink extends ChangeNotifier {
  LinkStatus _status = LinkStatus.disconnected;
  LinkStatus get status => _status;
  bool get isConnected => _status == LinkStatus.connected;

  BluetoothDevice? _device;
  BluetoothCharacteristic? _commandChar;
  StreamSubscription<BluetoothConnectionState>? _connSub;
  StreamSubscription<List<int>>? _stateSub;

  String? _error;
  String? get error => _error;

  GameState? _state;
  GameState? get state => _state;

  bool _writing = false;
  ({Player player, double x, double y})? _pendingMove;
  bool _pendingNewGame = false;

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
      // Licencia sin fines de lucro (uso personal/educativo).
      await device.connect(
        license: License.nonprofit,
        timeout: const Duration(seconds: 12),
      );

      _connSub = device.connectionState.listen((s) {
        if (s == BluetoothConnectionState.disconnected && _device == device) {
          _commandChar = null;
          _state = null;
          _error ??= 'Se perdió la conexión';
          _setStatus(LinkStatus.disconnected);
        }
      });

      final services = await device.discoverServices();
      final service = services
          .where((s) => s.uuid == GameProtocol.serviceUuid)
          .firstOrNull;
      final command = service?.characteristics
          .where((c) => c.uuid == GameProtocol.commandCharUuid)
          .firstOrNull;
      final state = service?.characteristics
          .where((c) => c.uuid == GameProtocol.stateCharUuid)
          .firstOrNull;
      if (command == null || state == null) {
        throw StateError('El dispositivo no expone el servicio del gato');
      }

      _commandChar = command;
      _stateSub = state.onValueReceived.listen((bytes) {
        final s = GameProtocol.decodeState(bytes);
        if (s == null) return;
        _state = s;
        notifyListeners();
      });
      await state.setNotifyValue(true);
      await state.read();
      _setStatus(LinkStatus.connected);
    } catch (e) {
      _error = _describe(e);
      await _teardown();
      _setStatus(LinkStatus.disconnected);
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
    _commandChar = null;
    _state = null;
    _pendingMove = null;
    _pendingNewGame = false;
    final d = _device;
    if (d != null && d.isConnected) {
      try {
        await d.disconnect();
      } catch (_) {}
    }
  }

  /// Pide mover la cabeza. Si hay otra escritura en curso, reemplaza al
  /// movimiento pendiente en vez de encolarse.
  void move(Player player, double xMm, double yMm) {
    if (!isConnected) return;
    _pendingMove = (player: player, x: xMm, y: yMm);
    if (!_writing) _drain();
  }

  void newGame() {
    if (!isConnected) return;
    _pendingNewGame = true;
    if (!_writing) _drain();
  }

  Future<void> _drain() async {
    _writing = true;
    try {
      while (_pendingNewGame || _pendingMove != null) {
        final char = _commandChar;
        if (char == null) break;
        final List<int> frame;
        if (_pendingNewGame) {
          _pendingNewGame = false;
          frame = GameProtocol.encodeNewGame();
        } else {
          final m = _pendingMove!;
          _pendingMove = null;
          frame = GameProtocol.encodeMove(m.player, m.x, m.y);
        }
        await char.write(frame, withoutResponse: true);
      }
    } catch (e) {
      _error = _describe(e);
      notifyListeners();
    } finally {
      _writing = false;
    }
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
