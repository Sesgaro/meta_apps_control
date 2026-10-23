import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:gato_cartesiano/ble/game_protocol.dart';
import 'package:gato_cartesiano/ble/pose_frame.dart';

const _g = BoardGeometry(
  xMin: 0,
  xMax: 180,
  yMin: 0,
  yMax: 180,
  x0: 30,
  xStep: 60,
  y0: 30,
  yStep: 60,
);

void main() {
  test('el espejo refleja al otro lado del tablero y cruza las manos', () {
    final d = ByteData(PoseFrame.length)
      ..setUint8(0, PoseFrame.header)
      ..setUint8(1, 1)
      ..setInt16(2, 40, Endian.little) // cabeza x
      ..setInt16(4, -300, Endian.little) // cabeza y (de tu lado)
      ..setInt16(6, 450, Endian.little) // altura
      ..setInt8(8, 0)
      ..setInt8(9, 127) // mira hacia el tablero (+Y)
      ..setInt16(11, 10, Endian.little) // mano izq x
      ..setInt16(17, 70, Endian.little) // mano der x
      ..setUint8(29, 1 | 0x10 | 4); // solo la izquierda, cerrada, con pelota
    final m = PoseFrame.mirror(d.buffer.asUint8List(), _g)!;
    final r = ByteData.sublistView(m);
    expect(m[1], PoseFrame.mirrorId);
    expect(r.getInt16(2, Endian.little), 140); // 180 - 40
    expect(r.getInt16(4, Endian.little), 480); // 180 + 300
    expect(r.getInt16(6, Endian.little), 450);
    expect(r.getInt8(9), -127);
    expect(r.getInt16(11, Endian.little), 110); // la der reflejada
    expect(r.getInt16(17, Endian.little), 170); // la izq reflejada
    expect(m[29], 2 | 0x20 | 4); // ahora es la derecha, cerrada
  });

  test('ignora tramas que no son de pose', () {
    expect(PoseFrame.mirror([1, 2, 3], _g), isNull);
  });
}
