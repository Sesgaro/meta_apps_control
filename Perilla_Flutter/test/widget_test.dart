import 'dart:typed_data';

import 'package:control_angulos/ble/angle_protocol.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('la trama codifica décimas de grado en int16 little-endian', () {
    expect(AngleProtocol.encode(90), [0xA5, 0, 0x84, 0x03]); // 900
    expect(AngleProtocol.encode(-45, channel: 2), [
      0xA5,
      2,
      0x3E,
      0xFE,
    ]); // -450
  });

  test('ida y vuelta conserva el ángulo con resolución de 0.1°', () {
    for (final a in [0.0, 12.3, 90.5, 179.9, 359.9, -180.0]) {
      final d = AngleProtocol.decode(AngleProtocol.encode(a, channel: 1))!;
      expect(d.channel, 1);
      expect(d.degrees, closeTo(a, 0.05));
    }
  });

  test('la trama de estado se lee con posición, referencia, rpm y PWM', () {
    final d = ByteData(AngleProtocol.stateLength)
      ..setUint8(0, AngleProtocol.stateHeader)
      ..setUint8(1, 0)
      ..setInt32(2, -4005, Endian.little) // -400.5°: más de una vuelta
      ..setInt32(6, 900, Endian.little)
      ..setInt16(10, -123, Endian.little)
      ..setInt16(12, -255, Endian.little);
    final s = AngleProtocol.decodeState(d.buffer.asUint8List())!;
    expect(s.position, closeTo(-400.5, 1e-9));
    expect(s.reference, 90);
    expect(s.rpm, closeTo(-12.3, 1e-9));
    expect(s.pwm, -255);
    expect(AngleProtocol.decodeState([0xA5, 0, 0, 0]), isNull);
  });

  test('el estado trae el modo compartido; 0xFF o 14 bytes = sin modo', () {
    List<int> frame(int? mode) {
      final d = ByteData(AngleProtocol.stateLength)
        ..setUint8(0, AngleProtocol.stateHeader)
        ..setUint8(14, mode ?? 0xFF);
      return d.buffer.asUint8List();
    }

    expect(AngleProtocol.decodeState(frame(2))!.mode, 2);
    expect(AngleProtocol.decodeState(frame(null))!.mode, isNull);
    expect(AngleProtocol.decodeState(frame(1).sublist(0, 14))!.mode, isNull);
    expect(AngleProtocol.encodeMode(1), [0xA7, 1]);
  });
}
