import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';

import 'game_protocol.dart';
import 'game_sim.dart';
import 'pose_frame.dart';

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

  /// Simulación sin ESP ([startDemo]); null con la máquina real.
  GameSim? _sim;
  Timer? _simTimer;
  bool get isDemo => _sim != null;

  BluetoothDevice? _device;
  BluetoothCharacteristic? _commandChar;
  BluetoothCharacteristic? _poseChar;
  StreamSubscription<List<int>>? _poseSub;

  /// Pose del otro visor (ver PoseFrame) recién llegada.
  void Function(Uint8List frame)? onPose;
  StreamSubscription<BluetoothConnectionState>? _connSub;
  StreamSubscription<List<int>>? _stateSub;

  String? _error;
  String? get error => _error;

  GameState? _state;
  GameState? get state => _state;

  bool _writing = false;
  ({Player player, double x, double y})? _pendingMove;
  bool _pendingNewGame = false;
  Player? _pendingFire;
  Uint8List? _pendingPose;

  String get deviceName {
    if (isDemo) return 'Simulación';
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
      final pose = service?.characteristics
          .where((c) => c.uuid == GameProtocol.poseCharUuid)
          .firstOrNull;
      if (pose != null) {
        _poseChar = pose;
        _poseSub = pose.onValueReceived.listen(
          (bytes) => onPose?.call(Uint8List.fromList(bytes)),
        );
        await pose.setNotifyValue(true);
      }
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

  /// Juega contra una máquina simulada (ver [GameSim]), sin BLE.
  Future<void> startDemo() async {
    await disconnect();
    final sim = _sim = GameSim();
    _state = sim.state;
    // Cada 100 ms, como la notificación de estado de la ESP.
    _simTimer = Timer.periodic(const Duration(milliseconds: 100), (_) {
      sim.tick(100);
      _state = sim.state;
      notifyListeners();
    });
    _setStatus(LinkStatus.connected);
  }

  Future<void> disconnect() async {
    _error = null;
    await _teardown();
    _setStatus(LinkStatus.disconnected);
  }

  Future<void> _teardown() async {
    _simTimer?.cancel();
    _simTimer = null;
    _sim = null;
    await _connSub?.cancel();
    _connSub = null;
    await _stateSub?.cancel();
    _stateSub = null;
    await _poseSub?.cancel();
    _poseSub = null;
    _commandChar = null;
    _poseChar = null;
    _pendingPose = null;
    _state = null;
    _pendingMove = null;
    _pendingNewGame = false;
    _pendingFire = null;
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
    if (_sim != null) return _sim!.move(player, xMm, yMm);
    _pendingMove = (player: player, x: xMm, y: yMm);
    if (!_writing) _drain();
  }

  /// Tirar (lanzar / soltar la garra). Sale después del último movimiento.
  void fire(Player player) {
    if (!isConnected) return;
    if (_sim != null) return _sim!.fire(player);
    _pendingFire = player;
    if (!_writing) _drain();
  }

  /// Manda la pose de este visor; si hay otra en camino, la reemplaza.
  void sendPose(Uint8List frame) {
    if (!isConnected) return;
    if (_sim != null) {
      final g = _state?.geometry;
      final echo = g == null ? null : PoseFrame.mirror(frame, g);
      if (echo != null) onPose?.call(echo);
      return;
    }
    if (_poseChar == null) return;
    _pendingPose = frame;
    if (!_writing) _drain();
  }

  void newGame() {
    if (!isConnected) return;
    if (_sim != null) return _sim!.newGame();
    _pendingNewGame = true;
    if (!_writing) _drain();
  }

  Future<void> _drain() async {
    _writing = true;
    try {
      while (_pendingNewGame ||
          _pendingMove != null ||
          _pendingFire != null ||
          _pendingPose != null) {
        final char = _commandChar;
        if (char == null) break;
        final pose = _pendingPose;
        if (pose != null &&
            !_pendingNewGame &&
            _pendingMove == null &&
            _pendingFire == null) {
          // Las poses van al final: primero lo que cambia el juego.
          _pendingPose = null;
          await _poseChar?.write(pose, withoutResponse: true);
          continue;
        }
        final List<int> frame;
        if (_pendingNewGame) {
          _pendingNewGame = false;
          frame = GameProtocol.encodeNewGame();
        } else if (_pendingMove != null) {
          final m = _pendingMove!;
          _pendingMove = null;
          frame = GameProtocol.encodeMove(m.player, m.x, m.y);
        } else {
          frame = GameProtocol.encodeFire(_pendingFire!);
          _pendingFire = null;
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
