/// Protocolo BLE entre la app y la ESP32-S3 del gato.
///
/// Debe coincidir byte a byte con `firmware/esp32s3_gato/esp32s3_gato.ino`.
///
/// Comandos, en [commandCharUuid] (escritura sin respuesta):
///   C0 jugador xLo xHi yLo yHi   mover la cabeza; int16 little-endian en
///                                décimas de mm. La ESP solo lo acepta si es
///                                el turno de ese jugador.
///   C1 01                        partida nueva
///   C2 jugador                   tirar: termina el turno ya (lanzar la
///                                pelota / soltar la garra) y se lee el IR
///
/// Estado, en [stateCharUuid] (lectura + notificación cada 100 ms), 45 bytes
/// little-endian; ver [GameState].
library;

import 'dart:typed_data';

import 'package:flutter_blue_plus/flutter_blue_plus.dart';

class GameProtocol {
  GameProtocol._();

  static const deviceName = 'ESP32S3-Gato';

  static final serviceUuid = Guid('7a1d1000-5e2f-4b8a-9c3d-2f1e0a6b4c11');
  static final commandCharUuid = Guid('7a1d1001-5e2f-4b8a-9c3d-2f1e0a6b4c11');
  static final stateCharUuid = Guid('7a1d1002-5e2f-4b8a-9c3d-2f1e0a6b4c11');

  /// Poses de los visores (ver PoseFrame); opcional en firmwares viejos.
  static final poseCharUuid = Guid('7a1d1003-5e2f-4b8a-9c3d-2f1e0a6b4c11');

  static const moveHeader = 0xC0;
  static const gameHeader = 0xC1;
  static const fireHeader = 0xC2;
  static const calibrateHeader = 0xC3;
  static const touchHeader = 0xC4;
  static const stateHeader = 0xC6;
  static const stateLength = 45;

  /// Mover la cabeza a ([xMm], [yMm]) a nombre de [player].
  static Uint8List encodeMove(Player player, double xMm, double yMm) {
    final d = ByteData(6)
      ..setUint8(0, moveHeader)
      ..setUint8(1, player.code)
      ..setInt16(2, _tenths(xMm), Endian.little)
      ..setInt16(4, _tenths(yMm), Endian.little);
    return d.buffer.asUint8List();
  }

  static Uint8List encodeFire(Player player) =>
      Uint8List.fromList([fireHeader, player.code]);

  /// Botón Calibrar (o Cancelar) de [player]; ver la calibración en la S3.
  static Uint8List encodeCalibrate(Player player) =>
      Uint8List.fromList([calibrateHeader, player.code]);

  /// [player] ya tocó la cabeza en el punto [step] (1 o 2) de la calibración.
  static Uint8List encodeTouch(Player player, int step) =>
      Uint8List.fromList([touchHeader, player.code, step]);

  static Uint8List encodeNewGame() => Uint8List.fromList([gameHeader, 0x01]);

  static GameState? decodeState(List<int> bytes) {
    if (bytes.length < stateLength || bytes[0] != stateHeader) return null;
    final d = ByteData.sublistView(Uint8List.fromList(bytes));
    double mm(int at) => d.getInt16(at, Endian.little) / 10;
    return GameState(
      phase: Phase.fromCode(bytes[1]),
      turn: Player.fromCode(bytes[2]),
      timeLeft: d.getInt16(3, Endian.little) / 10,
      board: [for (var i = 0; i < 9; i++) Player.fromCode(bytes[5 + i])],
      winner: bytes[14],
      lastResult: ShotResult.fromCode(bytes[15]),
      lastCell: bytes[16] < 9 ? bytes[16] : null,
      lastPlayer: Player.fromCode(bytes[17]),
      posX: mm(18),
      posY: mm(20),
      refX: mm(22),
      refY: mm(24),
      irCell: bytes[26] < 9 ? bytes[26] : null,
      irMultiple: bytes[26] == 0xFE,
      yAxisLinked: bytes[27] & 1 != 0,
      geometry: BoardGeometry(
        xMin: mm(28),
        xMax: mm(30),
        yMin: mm(32),
        yMax: mm(34),
        x0: mm(36),
        xStep: mm(38),
        y0: mm(40),
        yStep: mm(42),
      ),
      turnSeconds: bytes[44],
      // Firmwares sin calibración mandan 45 bytes.
      calibStep: bytes.length > 46 ? bytes[45] : 0,
      calibDone: bytes.length > 46 ? bytes[46] & 3 : 0,
      calibWho: bytes.length > 46 ? bytes[46] >> 4 & 3 : 0,
    );
  }

  static int _tenths(double mm) => (mm * 10).round().clamp(-32768, 32767);
}

enum Phase {
  waiting(0),
  turn(1),
  resolving(2),
  over(3);

  const Phase(this.code);
  final int code;

  static Phase fromCode(int c) =>
      Phase.values.firstWhere((p) => p.code == c, orElse: () => Phase.waiting);
}

/// Jugador o contenido de una casilla. [none] = casilla vacía.
enum Player {
  none(0, ''),
  x(1, 'X'),
  o(2, 'O');

  const Player(this.code, this.symbol);
  final int code;
  final String symbol;

  Player get other => this == x ? o : x;

  static Player fromCode(int c) =>
      Player.values.firstWhere((p) => p.code == c, orElse: () => Player.none);
}

enum ShotResult {
  none(0),
  placed(1),
  missed(2),
  occupied(3);

  const ShotResult(this.code);
  final int code;

  static ShotResult fromCode(int c) => ShotResult.values.firstWhere(
    (r) => r.code == c,
    orElse: () => ShotResult.none,
  );
}

/// Medidas del tablero en mm, como las tiene configuradas la ESP. La casilla
/// (col, fila) está centrada en (x0 + col·xStep, y0 + fila·yStep); índice =
/// fila·3 + col, fila 0 = Y mínima.
class BoardGeometry {
  const BoardGeometry({
    required this.xMin,
    required this.xMax,
    required this.yMin,
    required this.yMax,
    required this.x0,
    required this.xStep,
    required this.y0,
    required this.yStep,
  });

  final double xMin, xMax, yMin, yMax;
  final double x0, xStep, y0, yStep;

  double cellX(int cell) => x0 + (cell % 3) * xStep;
  double cellY(int cell) => y0 + (cell ~/ 3) * yStep;

  /// Bordes del tablero dibujado: media casilla más allá de las de los extremos.
  double get left => x0 - xStep / 2;
  double get right => x0 + 2.5 * xStep;
  double get bottom => y0 - yStep / 2;
  double get top => y0 + 2.5 * yStep;
}

class GameState {
  const GameState({
    required this.phase,
    required this.turn,
    required this.timeLeft,
    required this.board,
    required this.winner,
    required this.lastResult,
    required this.lastCell,
    required this.lastPlayer,
    required this.posX,
    required this.posY,
    required this.refX,
    required this.refY,
    required this.irCell,
    required this.irMultiple,
    required this.yAxisLinked,
    required this.geometry,
    required this.turnSeconds,
    this.calibStep = 0,
    this.calibDone = 0,
    this.calibWho = 0,
  });

  final Phase phase;
  final Player turn;

  /// Segundos que le quedan al turno.
  final double timeLeft;
  final List<Player> board;

  /// 0 nadie, 1 X, 2 O, 3 empate.
  final int winner;
  final ShotResult lastResult;
  final int? lastCell;
  final Player lastPlayer;

  /// Posición medida y referencia de la cabeza, en mm.
  final double posX, posY, refX, refY;

  /// Casilla que ve la matriz IR ahora (null: ninguna o varias).
  final int? irCell;
  final bool irMultiple;

  /// La ESP32 normal (eje X + sensores) responde por la UART.
  final bool yAxisLinked;
  final BoardGeometry geometry;
  final int turnSeconds;

  /// Calibración de los visores: punto al que va la cabeza (0 = no se
  /// calibra, 1 o 2) y quién ya lo tocó / quién calibra (bit0 X, bit1 O).
  final int calibStep, calibDone, calibWho;

  bool get isDraw => winner == 3;
  Player get winnerPlayer =>
      winner == 3 ? Player.none : Player.fromCode(winner);

  /// Datos para el panel 3D (PanelBus).
  Map<String, Object?> toMap() => {
    'phase': phase.code,
    'turn': turn.code,
    'timeLeft': timeLeft,
    'board': [for (final p in board) p.code],
    'winner': winner,
    'lastResult': lastResult.code,
    'lastCell': lastCell ?? -1,
    'lastPlayer': lastPlayer.code,
    'posX': posX,
    'posY': posY,
    'refX': refX,
    'refY': refY,
    'irCell': irCell ?? -1,
    'yAxisLinked': yAxisLinked,
    'xMin': geometry.xMin,
    'xMax': geometry.xMax,
    'yMin': geometry.yMin,
    'yMax': geometry.yMax,
    'x0': geometry.x0,
    'xStep': geometry.xStep,
    'y0': geometry.y0,
    'yStep': geometry.yStep,
    'turnSeconds': turnSeconds,
    'calibStep': calibStep,
    'calibDone': calibDone,
    'calibWho': calibWho,
  };
}
