import 'package:flutter/material.dart';

import '../theme/expressive_theme.dart';

/// Lienzo de dibujo (panel secreto del Quest).
///
/// Se dibuja pellizcando con la mano o con el gatillo del mando: para
/// Flutter ambos son un puntero que arrastra. El fondo es translúcido para
/// ver el entorno detrás del trazo.
class DrawingPanel extends StatefulWidget {
  const DrawingPanel({super.key});

  @override
  State<DrawingPanel> createState() => _DrawingPanelState();
}

class _Stroke {
  _Stroke(this.color, this.width);
  final Color color;
  final double width;
  final points = <Offset>[];
}

class _DrawingPanelState extends State<DrawingPanel> {
  final _strokes = <_Stroke>[];

  /// Se avisa en cada punto nuevo; repinta solo el lienzo, no la barra.
  final _changed = ValueNotifier(0);

  static const _widths = [4.0, 10.0, 22.0];
  Color? _color; // null = primario del tema
  double _width = _widths[1];

  @override
  void dispose() {
    _changed.dispose();
    super.dispose();
  }

  void _start(Offset p, Color color) {
    _strokes.add(_Stroke(color, _width)..points.add(p));
    _changed.value++;
  }

  void _extend(Offset p) {
    if (_strokes.isEmpty) return;
    _strokes.last.points.add(p);
    _changed.value++;
  }

  void _undo() {
    if (_strokes.isEmpty) return;
    _strokes.removeLast();
    _changed.value++;
  }

  void _clear() {
    _strokes.clear();
    _changed.value++;
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final palette = [
      scheme.primary,
      Colors.white,
      Colors.black,
      const Color(0xFF00BCD4),
      const Color(0xFFFFC107),
      const Color(0xFF4CAF50),
    ];
    final color = _color ?? scheme.primary;

    return Scaffold(
      backgroundColor: Colors.transparent,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            children: [
              Expanded(
                child: ClipRRect(
                  borderRadius: ExpressiveShapes.extraLarge,
                  child: ColoredBox(
                    color: scheme.surfaceContainer.withValues(alpha: 0.55),
                    // Eventos crudos: el trazo empieza al tocar, sin esperar a
                    // que se decida si es toque o arrastre. Un toque deja un punto.
                    child: Listener(
                      behavior: HitTestBehavior.opaque,
                      onPointerDown: (e) => _start(e.localPosition, color),
                      onPointerMove: (e) => _extend(e.localPosition),
                      child: RepaintBoundary(
                        child: CustomPaint(
                          painter: _StrokesPainter(_strokes, _changed),
                          size: Size.infinite,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Material(
                color: scheme.surfaceContainerHigh,
                borderRadius: ExpressiveShapes.pill,
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 8,
                  ),
                  child: Wrap(
                    alignment: WrapAlignment.center,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      for (final c in palette)
                        _Swatch(
                          color: c,
                          selected: c == color,
                          onTap: () => setState(() => _color = c),
                        ),
                      const SizedBox(width: 8),
                      for (final w in _widths)
                        _WidthButton(
                          width: w,
                          color: color,
                          selected: w == _width,
                          onTap: () => setState(() => _width = w),
                        ),
                      const SizedBox(width: 8),
                      IconButton.filledTonal(
                        tooltip: 'Deshacer',
                        onPressed: _undo,
                        icon: const Icon(Icons.undo_rounded),
                      ),
                      IconButton.filledTonal(
                        tooltip: 'Borrar todo',
                        onPressed: _clear,
                        icon: const Icon(Icons.delete_sweep_rounded),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _StrokesPainter extends CustomPainter {
  _StrokesPainter(this.strokes, Listenable changed) : super(repaint: changed);

  final List<_Stroke> strokes;

  @override
  void paint(Canvas canvas, Size size) {
    for (final s in strokes) {
      final paint = Paint()
        ..color = s.color
        ..strokeWidth = s.width
        ..style = PaintingStyle.stroke
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round;
      final pts = s.points;
      if (pts.length == 1) {
        canvas.drawCircle(
          pts.first,
          s.width / 2,
          paint..style = PaintingStyle.fill,
        );
        continue;
      }
      // Curvas por los puntos medios: trazo suave aunque el puntero del
      // visor llegue con saltos.
      final path = Path()..moveTo(pts.first.dx, pts.first.dy);
      for (var i = 1; i < pts.length - 1; i++) {
        final mid = (pts[i] + pts[i + 1]) / 2;
        path.quadraticBezierTo(pts[i].dx, pts[i].dy, mid.dx, mid.dy);
      }
      path.lineTo(pts.last.dx, pts.last.dy);
      canvas.drawPath(path, paint);
    }
  }

  @override
  bool shouldRepaint(_StrokesPainter old) => false;
}

class _Swatch extends StatelessWidget {
  const _Swatch({
    required this.color,
    required this.selected,
    required this.onTap,
  });

  final Color color;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkResponse(
      onTap: onTap,
      radius: 26,
      child: AnimatedContainer(
        duration: ExpressiveMotion.fast,
        width: 40,
        height: 40,
        decoration: BoxDecoration(
          color: color,
          shape: BoxShape.circle,
          border: Border.all(
            color: selected ? scheme.onSurface : scheme.outlineVariant,
            width: selected ? 4 : 1,
          ),
        ),
      ),
    );
  }
}

class _WidthButton extends StatelessWidget {
  const _WidthButton({
    required this.width,
    required this.color,
    required this.selected,
    required this.onTap,
  });

  final double width;
  final Color color;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkResponse(
      onTap: onTap,
      radius: 26,
      child: Container(
        width: 44,
        height: 44,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: selected ? scheme.secondaryContainer : null,
        ),
        child: Container(
          width: width.clamp(6, 26),
          height: width.clamp(6, 26),
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
      ),
    );
  }
}
