import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../ble/game_protocol.dart';
import '../../theme/expressive_theme.dart';

/// Tablero del gato visto desde arriba, en las medidas reales de la máquina.
///
/// Muestra la cuadrícula, las fichas, la casilla que ve la matriz IR, la
/// referencia (anillo) y la posición medida de la cabeza (punto). Si
/// [onMove] no es null (es tu turno), arrastrar mueve la cabeza.
class BoardView extends StatelessWidget {
  const BoardView({super.key, required this.state, this.onMove});

  final GameState state;
  final void Function(double xMm, double yMm)? onMove;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final view = _Viewport.of(state.geometry);

    return AspectRatio(
      aspectRatio: view.width / view.height,
      child: LayoutBuilder(
        builder: (context, box) {
          final size = box.biggest;
          void drag(Offset p) {
            final (x, y) = view.toMm(p, size);
            final g = state.geometry;
            onMove?.call(x.clamp(g.xMin, g.xMax), y.clamp(g.yMin, g.yMax));
          }

          return ClipRRect(
            borderRadius: ExpressiveShapes.large,
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onPanStart: onMove == null ? null : (d) => drag(d.localPosition),
              onPanUpdate: onMove == null ? null : (d) => drag(d.localPosition),
              onTapDown: onMove == null ? null : (d) => drag(d.localPosition),
              child: CustomPaint(
                size: size,
                painter: _BoardPainter(
                  state,
                  view,
                  scheme,
                  active: onMove != null,
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// Zona dibujada en mm: el recorrido de la cabeza unido al tablero.
class _Viewport {
  _Viewport(this.left, this.right, this.bottom, this.top);

  factory _Viewport.of(BoardGeometry g) {
    const margin = 8.0;
    return _Viewport(
      math.min(g.xMin, g.left) - margin,
      math.max(g.xMax, g.right) + margin,
      math.min(g.yMin, g.bottom) - margin,
      math.max(g.yMax, g.top) + margin,
    );
  }

  final double left, right, bottom, top;
  double get width => right - left;
  double get height => top - bottom;

  /// Y crece hacia arriba, como en la máquina.
  Offset toPx(double x, double y, Size s) =>
      Offset((x - left) / width * s.width, (top - y) / height * s.height);

  (double, double) toMm(Offset p, Size s) =>
      (left + p.dx / s.width * width, top - p.dy / s.height * height);
}

class _BoardPainter extends CustomPainter {
  _BoardPainter(this.state, this.view, this.scheme, {required this.active});

  final GameState state;
  final _Viewport view;
  final ColorScheme scheme;
  final bool active;

  @override
  void paint(Canvas canvas, Size size) {
    final g = state.geometry;
    Offset px(double x, double y) => view.toPx(x, y, size);
    final scale = size.width / view.width; // px por mm

    canvas.drawRect(
      Offset.zero & size,
      Paint()..color = scheme.surfaceContainerHighest,
    );

    // Recorrido de la cabeza.
    canvas.drawRect(
      Rect.fromPoints(px(g.xMin, g.yMax), px(g.xMax, g.yMin)),
      Paint()
        ..color = scheme.outlineVariant
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5,
    );

    // Tablero y cuadrícula.
    final board = Rect.fromPoints(px(g.left, g.top), px(g.right, g.bottom));
    canvas.drawRRect(
      RRect.fromRectAndRadius(board, const Radius.circular(12)),
      Paint()
        ..color = active ? scheme.primaryContainer : scheme.surfaceContainer,
    );
    final grid = Paint()
      ..color = scheme.outline
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.round;
    for (var i = 1; i <= 2; i++) {
      final x = g.left + i * g.xStep;
      final y = g.bottom + i * g.yStep;
      canvas.drawLine(px(x, g.bottom), px(x, g.top), grid);
      canvas.drawLine(px(g.left, y), px(g.right, y), grid);
    }

    Rect cellRect(int c) {
      final cx = g.cellX(c), cy = g.cellY(c);
      return Rect.fromPoints(
        px(cx - g.xStep / 2, cy + g.yStep / 2),
        px(cx + g.xStep / 2, cy - g.yStep / 2),
      ).deflate(5);
    }

    // Casilla que ve la matriz IR ahora.
    final ir = state.irCell;
    if (ir != null) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(cellRect(ir), const Radius.circular(10)),
        Paint()..color = scheme.tertiary.withValues(alpha: 0.25),
      );
    }

    // Fichas.
    final pieceR = math.min(g.xStep, g.yStep) * 0.32 * scale;
    for (var c = 0; c < 9; c++) {
      final p = state.board[c];
      if (p == Player.none) continue;
      final center = px(g.cellX(c), g.cellY(c));
      final stroke = Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = pieceR * 0.28
        ..strokeCap = StrokeCap.round;
      if (p == Player.x) {
        stroke.color = scheme.primary;
        canvas.drawLine(
          center + Offset(-pieceR, -pieceR),
          center + Offset(pieceR, pieceR),
          stroke,
        );
        canvas.drawLine(
          center + Offset(-pieceR, pieceR),
          center + Offset(pieceR, -pieceR),
          stroke,
        );
      } else {
        stroke.color = scheme.secondary;
        canvas.drawCircle(center, pieceR, stroke);
      }
    }

    // Última jugada.
    final last = state.lastCell;
    if (last != null && state.lastResult != ShotResult.none) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(cellRect(last), const Radius.circular(10)),
        Paint()
          ..color = state.lastResult == ShotResult.placed
              ? scheme.primary
              : scheme.error
          ..style = PaintingStyle.stroke
          ..strokeWidth = 3,
      );
    }

    // Referencia (a dónde va) y cabeza (dónde está).
    final headR = math.max(6.0, 5 * scale);
    canvas.drawCircle(
      px(state.refX, state.refY),
      headR + 4,
      Paint()
        ..color = scheme.onSurface.withValues(alpha: 0.6)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2,
    );
    final head = px(state.posX, state.posY);
    canvas.drawCircle(head, headR, Paint()..color = const Color(0xFFFF9E2E));
    canvas.drawCircle(head, headR * 0.4, Paint()..color = Colors.white);
  }

  @override
  bool shouldRepaint(_BoardPainter old) =>
      old.state != state || old.scheme != scheme || old.active != active;
}
