import 'dart:math';

import 'game_protocol.dart';

/// Máquina del gato simulada, para probar la app sin la ESP ni el tablero.
///
/// Copia las reglas de `firmware/esp32s3_gato/gato.h` y la configuración de
/// `esp32s3_gato.ino` (geometría, turno de 20 s, quieto 300 ms, resolver 4 s).
/// La cabeza va hacia la referencia a la velocidad de cada eje y la "matriz
/// IR" ve la casilla cuyo centro tiene la cabeza cerca.
class GameSim {
  static const geometry = BoardGeometry(
    xMin: 0,
    xMax: 180,
    yMin: 0,
    yMax: 180,
    x0: 30,
    xStep: 60,
    y0: 30,
    yStep: 60,
  );
  static const turnMs = 20000;
  static const stillMs = 300;
  static const resolveMaxMs = 12000;
  static const toleranceMm = 2.0;

  /// mm/s: cremallera en X; husillo T8 en Y (~17 mm/s, ver esp32s3_gato.ino).
  static const speedX = 150.0, speedY = 17.0;

  /// shortcut: radio supuesto de lo que ve cada sensor IR; ajustar si se mide.
  static const irRadiusMm = 20.0;

  /// Calibración (ver esp32s3_gato.ino): en la simulación calibra uno solo.
  static const calibX = [30.0, 150.0], calibY = [30.0, 150.0];
  int calibStep = 0, calibDone = 0, calibWho = 0;

  Phase phase = Phase.waiting;
  Player turn = Player.x;
  Player _starts = Player.x;
  final board = List.filled(9, Player.none);
  int winner = 0;
  ShotResult lastResult = ShotResult.none;
  int? lastCell;
  Player lastPlayer = Player.none;
  double posX = 0, posY = 0, refX = 0, refY = 0;

  int _now = 0, _deadline = 0, _resolveStart = 0;
  int? _stillSince;

  void newGame() {
    calibStep = 0;
    board.fillRange(0, 9, Player.none);
    turn = _starts;
    _starts = _starts.other;
    winner = 0;
    lastResult = ShotResult.none;
    lastCell = null;
    lastPlayer = Player.none;
    _startTurn();
  }

  void move(Player player, double x, double y) {
    if (phase != Phase.turn || player != turn) return;
    refX = x.clamp(geometry.xMin, geometry.xMax);
    refY = y.clamp(geometry.yMin, geometry.yMax);
  }

  /// Tirar: termina el turno de [player] ya.
  void fire(Player player) {
    if (phase != Phase.turn || player != turn) return;
    _startResolving();
  }

  /// Botón Calibrar: empieza (con la partida en espera o terminada) o cancela.
  void calibrate(Player player) {
    if (calibStep != 0) {
      calibStep = 0;
    } else if (phase == Phase.waiting || phase == Phase.over) {
      calibWho = 1 << (player.code - 1);
      _calibPoint(1);
    }
  }

  void touch(Player player, int step) {
    if (step != calibStep) return;
    calibDone |= 1 << (player.code - 1);
    if (calibDone & calibWho == calibWho) {
      if (calibStep == 1) {
        _calibPoint(2);
      } else {
        calibStep = 0;
      }
    }
  }

  void _calibPoint(int step) {
    calibStep = step;
    calibDone = 0;
    refX = calibX[step - 1];
    refY = calibY[step - 1];
  }

  int? get irCell {
    for (var i = 0; i < 9; i++) {
      if ((posX - geometry.cellX(i)).abs() <= irRadiusMm &&
          (posY - geometry.cellY(i)).abs() <= irRadiusMm) {
        return i;
      }
    }
    return null;
  }

  bool get _headStill =>
      (posX - refX).abs() <= toleranceMm && (posY - refY).abs() <= toleranceMm;

  /// Avanza la simulación [dtMs] milisegundos.
  void tick(int dtMs) {
    _now += dtMs;
    final dt = dtMs / 1000;
    posX += (refX - posX).clamp(-speedX * dt, speedX * dt);
    posY += (refY - posY).clamp(-speedY * dt, speedY * dt);

    if (phase == Phase.turn) {
      if (_now >= _deadline) _startResolving();
      return;
    }
    if (phase != Phase.resolving) return;
    _stillSince = _headStill ? (_stillSince ?? _now) : null;
    final settled = _stillSince != null && _now - _stillSince! >= stillMs;
    if (settled || _now - _resolveStart >= resolveMaxMs) _resolve(irCell);
  }

  void _startResolving() {
    phase = Phase.resolving;
    _resolveStart = _now;
    _stillSince = null;
  }

  void _startTurn() {
    phase = Phase.turn;
    _deadline = _now + turnMs;
  }

  void _resolve(int? cell) {
    lastPlayer = turn;
    lastCell = cell;
    if (cell == null) {
      lastResult = ShotResult.missed;
    } else if (board[cell] == Player.none) {
      board[cell] = turn;
      lastResult = ShotResult.placed;
    } else {
      lastResult = ShotResult.occupied;
    }
    winner = _winner();
    if (winner != 0) {
      phase = Phase.over;
      return;
    }
    turn = turn.other;
    _startTurn();
  }

  int _winner() {
    const lines = [
      [0, 1, 2], [3, 4, 5], [6, 7, 8], //
      [0, 3, 6], [1, 4, 7], [2, 5, 8], //
      [0, 4, 8], [2, 4, 6],
    ];
    for (final l in lines) {
      final p = board[l[0]];
      if (p != Player.none && p == board[l[1]] && p == board[l[2]]) {
        return p.code;
      }
    }
    return board.contains(Player.none) ? 0 : 3;
  }

  GameState get state => GameState(
    phase: phase,
    turn: turn,
    timeLeft: phase == Phase.turn ? max(0, _deadline - _now) / 1000 : 0,
    board: List.of(board),
    winner: winner,
    lastResult: lastResult,
    lastCell: lastCell,
    lastPlayer: lastPlayer,
    posX: posX,
    posY: posY,
    refX: refX,
    refY: refY,
    irCell: irCell,
    irMultiple: false,
    yAxisLinked: true,
    geometry: geometry,
    turnSeconds: turnMs ~/ 1000,
    calibStep: calibStep,
    calibDone: calibDone,
    calibWho: calibWho,
  );
}
