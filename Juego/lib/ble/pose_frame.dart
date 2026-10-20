import 'dart:typed_data';

import 'game_protocol.dart';

/// Pose de un visor para que el otro lo vea (cabeza, manos y pelota), en la
/// característica de poses. La arma y la dibuja el tablero 3D
/// (GameActivity3D.kt, `PoseFrame`); la app y la ESP solo la reenvían.
///
/// 30 bytes little-endian. Coordenadas del tablero: x/y en mm de la máquina,
/// h = altura sobre la mesa en mm (a la escala de la calibración).
///   [0] D0  [1] jugador (1 X, 2 O; FF = espejo de la simulación)
///   [2..7]   cabeza x y h (int16)   [8..10] hacia dónde mira x y h (int8, ×127)
///   [11..16] mano izquierda         [17..22] mano derecha
///   [23..28] pelota                 [29] banderas: bit0 mano izq, bit1 mano
///                                        der, bit2 pelota, bit3 garra abajo
class PoseFrame {
  PoseFrame._();

  static const header = 0xD0;
  static const length = 30;
  static const mirrorId = 0xFF;

  static const _points = [2, 11, 17, 23];

  /// En la simulación no hay otro visor: se devuelve tu propia pose
  /// reflejada al otro lado del tablero, como si el rival estuviera enfrente.
  static Uint8List? mirror(List<int> frame, BoardGeometry g) {
    if (frame.length != length || frame[0] != header) return null;
    final out = Uint8List.fromList(frame);
    final d = ByteData.sublistView(out);
    final cx = g.left + g.right;
    final cy = g.bottom + g.top;
    for (final at in _points) {
      d.setInt16(
        at,
        (cx - d.getInt16(at, Endian.little)).round(),
        Endian.little,
      );
      d.setInt16(
        at + 2,
        (cy - d.getInt16(at + 2, Endian.little)).round(),
        Endian.little,
      );
    }
    d.setInt8(8, -d.getInt8(8).clamp(-127, 127));
    d.setInt8(9, -d.getInt8(9).clamp(-127, 127));
    // Manos cruzadas: tu izquierda es la derecha del reflejo.
    final left = out.sublist(11, 17);
    out.setRange(11, 17, out.sublist(17, 23));
    out.setRange(17, 23, left);
    final f = out[29];
    out[29] = (f & ~3) | ((f & 1) << 1) | ((f & 2) >> 1);
    out[1] = mirrorId;
    return out;
  }
}
