/// Protocolo entre la app y la ESP32-S3.
///
/// Debe coincidir byte a byte con `firmware/esp32s3_receptor_angulos`.
///
/// Trama binaria de 4 bytes, escrita sin respuesta en [angleCharUuid]:
///
///   [0] 0xA5        cabecera (distingue la trama de ángulo de la de modo)
///   [1] canal       0..255, por si luego hay varios servos/ejes
///   [2] ángulo lo   int16 little-endian, en DÉCIMAS de grado
///   [3] ángulo hi   (90.5° -> 905, -45° -> -450)
///
/// En sentido contrario, la ESP publica su estado en [stateCharUuid]
/// (lectura + notificación, ~10 Hz), trama de 15 bytes little-endian:
///
///   [0]      0xA6        cabecera de estado
///   [1]      canal
///   [2..5]   posición    int32, décimas de grado (medida con el encoder)
///   [6..9]   referencia  int32, décimas de grado (último ángulo recibido)
///   [10..11] rpm         int16, décimas
///   [12..13] PWM         int16, -255..255
///   [14]     modo        índice de rango de la perilla; 0xFF = sin definir
///
/// El modo lo fija cualquier app con la trama [encodeMode] (`A7 modo`) y la
/// ESP solo lo guarda y lo reparte, para que todos muestren el mismo rango.
/// El firmware anterior manda 14 bytes, sin modo.
library;

import 'dart:typed_data';

import 'package:flutter_blue_plus/flutter_blue_plus.dart';

class AngleProtocol {
  AngleProtocol._();

  /// Nombre con el que anuncia el firmware.
  static const deviceName = 'ESP32S3-Angulos';

  static final serviceUuid = Guid('7a1d0000-5e2f-4b8a-9c3d-2f1e0a6b4c11');
  static final angleCharUuid = Guid('7a1d0001-5e2f-4b8a-9c3d-2f1e0a6b4c11');
  static final stateCharUuid = Guid('7a1d0002-5e2f-4b8a-9c3d-2f1e0a6b4c11');

  static const header = 0xA5;
  static const frameLength = 4;

  static const stateHeader = 0xA6;
  static const stateLength = 15;

  static const modeHeader = 0xA7;

  /// Trama para fijar el modo de rango compartido.
  static Uint8List encodeMode(int mode) {
    assert(mode >= 0 && mode < 0xFF);
    return Uint8List.fromList([modeHeader, mode]);
  }

  /// Codifica [degrees] para [channel]. Redondea a 0.1° y satura al rango
  /// de int16 (±3276.7°), más que suficiente para cualquier ángulo útil.
  static Uint8List encode(double degrees, {int channel = 0}) {
    assert(channel >= 0 && channel <= 255);
    final tenths = (degrees * 10).round().clamp(-32768, 32767);
    final data = ByteData(frameLength)
      ..setUint8(0, header)
      ..setUint8(1, channel)
      ..setInt16(2, tenths, Endian.little);
    return data.buffer.asUint8List();
  }

  /// Inverso de [encode]; solo se usa en pruebas.
  static ({int channel, double degrees})? decode(List<int> bytes) {
    if (bytes.length != frameLength || bytes[0] != header) return null;
    final data = ByteData.sublistView(Uint8List.fromList(bytes));
    return (
      channel: data.getUint8(1),
      degrees: data.getInt16(2, Endian.little) / 10,
    );
  }

  /// Lee la trama de estado de la ESP; null si no es una trama válida.
  static MotorState? decodeState(List<int> bytes) {
    // 14 bytes: firmware anterior, sin modo.
    if (bytes.length < stateLength - 1 || bytes[0] != stateHeader) return null;
    final data = ByteData.sublistView(Uint8List.fromList(bytes));
    return MotorState(
      channel: data.getUint8(1),
      position: data.getInt32(2, Endian.little) / 10,
      reference: data.getInt32(6, Endian.little) / 10,
      rpm: data.getInt16(10, Endian.little) / 10,
      pwm: data.getInt16(12, Endian.little),
      mode: bytes.length >= stateLength && bytes[14] != 0xFF ? bytes[14] : null,
    );
  }
}

/// Lo que reporta la ESP sobre el motor.
class MotorState {
  const MotorState({
    required this.channel,
    required this.position,
    required this.reference,
    required this.rpm,
    required this.pwm,
    this.mode,
  });

  final int channel;

  /// Posición medida, en grados. Puede pasar de 360 si el eje da vueltas.
  final double position;

  /// Ángulo objetivo que tiene la ESP.
  final double reference;
  final double rpm;

  /// Salida del control, -255..255.
  final int pwm;

  /// Modo de rango compartido (índice de `AngleRange`); null si ninguna app
  /// lo ha fijado o el firmware no lo soporta.
  final int? mode;
}
