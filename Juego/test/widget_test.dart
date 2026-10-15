import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:gato_cartesiano/ble/game_protocol.dart';

/// Trama de estado como la arma publicarEstado() en la ESP32-S3.
List<int> _stateFrame() {
  final d = ByteData(GameProtocol.stateLength)
    ..setUint8(0, GameProtocol.stateHeader)
    ..setUint8(1, 1) // fase: turno
    ..setUint8(2, 2) // turno de O
    ..setInt16(3, 123, Endian.little); // 12.3 s
  const board = [1, 0, 2, 0, 1, 0, 0, 0, 0];
  for (var i = 0; i < 9; i++) {
    d.setUint8(5 + i, board[i]);
  }
  d
    ..setUint8(14, 0) // sin ganador
    ..setUint8(15, 2) // último: falló
    ..setUint8(16, 0xFF) // sin casilla
    ..setUint8(17, 1) // lo hizo X
    ..setInt16(18, 905, Endian.little) // posX 90.5
    ..setInt16(20, 300, Endian.little) // posY 30.0
    ..setInt16(22, 900, Endian.little) // refX
    ..setInt16(24, 310, Endian.little) // refY
    ..setUint8(26, 1) // el IR ve la casilla 1
    ..setUint8(27, 1) // enlace UART ok
    ..setInt16(28, 0, Endian.little)
    ..setInt16(30, 1800, Endian.little)
    ..setInt16(32, 0, Endian.little)
    ..setInt16(34, 1800, Endian.little)
    ..setInt16(36, 300, Endian.little)
    ..setInt16(38, 600, Endian.little)
    ..setInt16(40, 300, Endian.little)
    ..setInt16(42, 600, Endian.little)
    ..setUint8(44, 20);
  return d.buffer.asUint8List();
}

void main() {
  test('mover: jugador y décimas de mm en int16 little-endian', () {
    expect(GameProtocol.encodeMove(Player.o, 90.5, 30), [
      0xC0,
      2,
      0x89,
      0x03,
      0x2C,
      0x01,
    ]);
    expect(GameProtocol.encodeNewGame(), [0xC1, 0x01]);
  });

  test('el estado se lee igual que lo publica la ESP', () {
    final s = GameProtocol.decodeState(_stateFrame())!;
    expect(s.phase, Phase.turn);
    expect(s.turn, Player.o);
    expect(s.timeLeft, closeTo(12.3, 1e-9));
    expect(s.board.take(3), [Player.x, Player.none, Player.o]);
    expect(s.winner, 0);
    expect(s.lastResult, ShotResult.missed);
    expect(s.lastCell, isNull);
    expect(s.lastPlayer, Player.x);
    expect(s.posX, closeTo(90.5, 1e-9));
    expect(s.refY, closeTo(31, 1e-9));
    expect(s.irCell, 1);
    expect(s.yAxisLinked, isTrue);
    expect(s.geometry.xMax, 180);
    expect(s.turnSeconds, 20);
  });

  test('geometría: centros de casilla y bordes del tablero', () {
    final g = GameProtocol.decodeState(_stateFrame())!.geometry;
    expect(g.cellX(0), 30);
    expect(g.cellX(5), 150); // columna 2
    expect(g.cellY(5), 90); // fila 1
    expect(g.cellY(8), 150);
    expect(g.left, 0);
    expect(g.right, 180);
  });

  test('tramas cortas o ajenas se ignoran', () {
    expect(GameProtocol.decodeState([0xC6, 1, 2]), isNull);
    expect(GameProtocol.decodeState(List.filled(45, 0)), isNull);
  });
}
