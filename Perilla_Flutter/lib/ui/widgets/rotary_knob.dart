import 'dart:math' as math;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../theme/expressive_theme.dart';

/// Perilla rotativa al estilo Material 3 Expressive.
///
/// - Cuerpo con forma de "galleta" (cookie de 9 lóbulos), una de las formas
///   del catálogo M3E; gira con el valor.
/// - Pista ondulada para la parte activa, como el indicador de progreso
///   ondulado de M3E. La onda crece mientras se arrastra.
/// - Háptica en cada [detent] grados y un golpe más fuerte en los topes.
///
/// El arrastre es por ángulo relativo: lo que cuenta es cuánto gira el dedo
/// alrededor del centro, no dónde toca. Así la perilla no "salta" al tocarla.
/// La rueda del ratón o el joystick (mando de Quest) también la giran.
class RotaryKnob extends StatefulWidget {
  const RotaryKnob({
    super.key,
    required this.value,
    required this.min,
    required this.max,
    required this.onChanged,
    this.onChangeEnd,
    this.wrap = false,
    this.sweepDegrees = 270,
    this.step = 1,
    this.detent = 5,
    this.enabled = true,
    this.measured,
  });

  final double value;
  final double min;
  final double max;

  /// Rango circular (0–360): al pasar de max se vuelve a min y la perilla da
  /// la vuelta completa. Si es false, recorre [sweepDegrees] con topes.
  final bool wrap;

  /// Recorrido visual con topes, centrado arriba. Si coincide con el rango
  /// (p. ej. -90..90 en 180°) la perilla gira exactamente lo que vale: 0°
  /// arriba, -90° a la izquierda y +90° a la derecha.
  final double sweepDegrees;

  /// Resolución del valor entregado.
  final double step;

  /// Cada cuántos grados se nota un "clic" háptico.
  final double detent;

  final bool enabled;

  /// Posición real que reporta la ESP; se marca en la pista con otro color.
  final double? measured;

  final ValueChanged<double> onChanged;
  final ValueChanged<double>? onChangeEnd;

  @override
  State<RotaryKnob> createState() => _RotaryKnobState();
}

class _RotaryKnobState extends State<RotaryKnob> with TickerProviderStateMixin {
  /// Valor que se pinta. Durante el arrastre sigue al dedo; ante cambios
  /// externos (presets, campo numérico) se anima hasta el nuevo valor.
  late final AnimationController _settle = AnimationController(
    vsync: this,
    duration: ExpressiveMotion.slow,
  );
  late Animation<double> _shown = AlwaysStoppedAnimation(widget.value);

  late final AnimationController _press = AnimationController(
    vsync: this,
    duration: ExpressiveMotion.fast,
  );

  bool _dragging = false;
  double? _lastPointerAngle;

  /// Valor sin redondear acumulado durante el arrastre. Si se redondeara en
  /// cada evento, los giros lentos (deltas < step) nunca avanzarían.
  double _raw = 0;

  @override
  void didUpdateWidget(RotaryKnob old) {
    super.didUpdateWidget(old);
    if (_dragging || old.value == widget.value) return;

    final from = _shown.value;
    var to = widget.value;
    // En modo circular, ir por el camino corto (350° -> 10° gira 20°, no 340°).
    if (widget.wrap) {
      final span = widget.max - widget.min;
      final diff = ((to - from + span / 2) % span) - span / 2;
      to = from + diff;
    }
    _shown = Tween(begin: from, end: to).animate(
      CurvedAnimation(parent: _settle, curve: ExpressiveMotion.spatial),
    );
    _settle.forward(from: 0);
  }

  @override
  void dispose() {
    _settle.dispose();
    _press.dispose();
    super.dispose();
  }

  double get _span => widget.max - widget.min;
  double get _sweep => _sweepRadians(widget.wrap, widget.sweepDegrees);

  double _pointerAngle(Offset local, Size size) {
    final c = size.center(Offset.zero);
    return math.atan2(local.dy - c.dy, local.dx - c.dx);
  }

  void _onStart(DragStartDetails d, Size size) {
    if (!widget.enabled) return;
    _dragging = true;
    _settle.stop();
    _raw = widget.value;
    _shown = AlwaysStoppedAnimation(widget.value);
    _lastPointerAngle = _pointerAngle(d.localPosition, size);
    _press.forward();
  }

  void _onUpdate(DragUpdateDetails d, Size size) {
    if (!_dragging) return;
    final a = _pointerAngle(d.localPosition, size);
    var delta = a - _lastPointerAngle!;
    // atan2 salta de +π a -π al cruzar el lado izquierdo.
    if (delta > math.pi) delta -= 2 * math.pi;
    if (delta < -math.pi) delta += 2 * math.pi;
    _lastPointerAngle = a;

    _advance(delta * _span / _sweep);
  }

  /// Mueve el valor acumulado [by] unidades, con topes, háptica y aviso.
  void _advance(double by) {
    final before = _snap(_raw);
    _raw += by;

    if (widget.wrap) {
      _raw = widget.min + (_raw - widget.min) % _span;
    } else if (_raw <= widget.min || _raw >= widget.max) {
      final hitEdge = before != widget.min && before != widget.max;
      _raw = _raw.clamp(widget.min, widget.max);
      if (hitEdge) HapticFeedback.mediumImpact();
    }

    final next = _snap(_raw);
    if (next == before) return;
    if ((next / widget.detent).floor() != (before / widget.detent).floor()) {
      HapticFeedback.selectionClick();
    }
    setState(() => _shown = AlwaysStoppedAnimation(next));
    widget.onChanged(next);
  }

  /// Rueda del ratón o joystick: cada muesca avanza un [detent].
  void _onScroll(PointerSignalEvent e) {
    if (!widget.enabled || _dragging || e is! PointerScrollEvent) return;
    final dy = e.scrollDelta.dy != 0 ? e.scrollDelta.dy : -e.scrollDelta.dx;
    if (dy == 0) return;
    GestureBinding.instance.pointerSignalResolver.register(e, (_) {
      _settle.stop();
      _raw = widget.value;
      _shown = AlwaysStoppedAnimation(widget.value);
      // Hacia arriba (dy < 0) sube el valor.
      _advance(-dy.sign * widget.detent);
      widget.onChangeEnd?.call(_snap(_raw));
    });
  }

  void _onEnd() {
    if (!_dragging) return;
    _dragging = false;
    _lastPointerAngle = null;
    _press.reverse();
    widget.onChangeEnd?.call(_snap(_raw));
  }

  double _snap(double v) {
    final s = (v / widget.step).round() * widget.step;
    return widget.wrap && s >= widget.max ? widget.min : s;
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;

    return AspectRatio(
      aspectRatio: 1,
      child: LayoutBuilder(
        builder: (context, box) {
          final size = box.biggest;
          return Listener(
            onPointerSignal: _onScroll,
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onPanStart: (d) => _onStart(d, size),
              onPanUpdate: (d) => _onUpdate(d, size),
              onPanEnd: (_) => _onEnd(),
              onPanCancel: _onEnd,
              child: AnimatedBuilder(
                animation: Listenable.merge([_settle, _press]),
                builder: (context, _) {
                  final shown = _shown.value;
                  final press = Curves.easeOut.transform(_press.value);
                  final display = widget.wrap
                      ? widget.min + (shown - widget.min) % _span
                      : shown.clamp(widget.min, widget.max);

                  return Stack(
                    alignment: Alignment.center,
                    children: [
                      CustomPaint(
                        size: size,
                        painter: _KnobPainter(
                          value: shown,
                          min: widget.min,
                          max: widget.max,
                          wrap: widget.wrap,
                          sweepDegrees: widget.sweepDegrees,
                          measured: widget.measured,
                          press: press,
                          enabled: widget.enabled,
                          scheme: scheme,
                        ),
                      ),
                      IgnorePointer(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              '${_format(display)}°',
                              style: text.displayLarge?.copyWith(
                                color: widget.enabled
                                    ? scheme.onPrimaryContainer
                                    : scheme.onSurface.withValues(alpha: 0.38),
                                fontFeatures: const [
                                  FontFeature.tabularFigures(),
                                ],
                                fontSize: size.width * 0.16,
                              ),
                            ),
                            Text(
                              _dragging
                                  ? 'Girando'
                                  : widget.measured == null
                                  ? 'Ángulo'
                                  : 'Real ${_format(widget.measured!)}°',
                              style: text.labelLarge?.copyWith(
                                color: scheme.onPrimaryContainer.withValues(
                                  alpha: 0.7,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  );
                },
              ),
            ),
          );
        },
      ),
    );
  }
}

double _sweepRadians(bool wrap, double sweepDegrees) =>
    wrap ? 2 * math.pi : sweepDegrees.clamp(1, 359) * math.pi / 180;

String _format(double v) {
  final r = (v * 10).round() / 10;
  return r == r.roundToDouble() ? r.toInt().toString() : r.toStringAsFixed(1);
}

class _KnobPainter extends CustomPainter {
  _KnobPainter({
    required this.value,
    required this.min,
    required this.max,
    required this.wrap,
    required this.sweepDegrees,
    required this.measured,
    required this.press,
    required this.enabled,
    required this.scheme,
  });

  final double value, min, max;
  final bool wrap;
  final double sweepDegrees;
  final double? measured;
  final double press;
  final bool enabled;
  final ColorScheme scheme;

  // Ángulos de canvas: 0 = derecha, positivo = horario. El recorrido con
  // topes se centra arriba (-π/2); el circular empieza arriba.
  double get _sweep => _sweepRadians(wrap, sweepDegrees);
  double get _start => wrap ? -math.pi / 2 : -math.pi / 2 - _sweep / 2;

  double _toAngle(double v) => _start + (v - min) / (max - min) * _sweep;

  @override
  void paint(Canvas canvas, Size size) {
    final c = size.center(Offset.zero);
    final outer = size.shortestSide / 2;
    final trackR = outer - 14;
    final active = enabled
        ? scheme.primary
        : scheme.onSurface.withValues(alpha: 0.38);

    // Pista inactiva.
    canvas.drawArc(
      Rect.fromCircle(center: c, radius: trackR),
      _start,
      _sweep,
      false,
      Paint()
        ..color = scheme.surfaceContainerHighest
        ..style = PaintingStyle.stroke
        ..strokeWidth = 8
        ..strokeCap = StrokeCap.round,
    );

    // Pista activa ondulada. Arranca en 0 si el rango lo incluye (útil en
    // -90..90), si no en el mínimo. En modo circular, desde arriba.
    final origin = wrap ? min : 0.0.clamp(min, max);
    final v = wrap ? min + (value - min) % (max - min) : value.clamp(min, max);
    final a0 = _toAngle(origin);
    final a1 = _toAngle(v);
    if ((a1 - a0).abs() > 0.001) {
      final amplitude = 3.0 + 3.0 * press;
      final waves = (2 * math.pi * trackR / 26).roundToDouble();
      final path = Path();
      const steps = 240;
      for (var i = 0; i <= steps; i++) {
        final t = a0 + (a1 - a0) * i / steps;
        // Atenuar la onda en los extremos para que el trazo nazca y muera
        // plano, como el indicador ondulado de M3E.
        final edge = math.min(1.0, math.min(i, steps - i) / 12);
        final r = trackR + amplitude * edge * math.sin(t * waves);
        final p = c + Offset(math.cos(t), math.sin(t)) * r;
        i == 0 ? path.moveTo(p.dx, p.dy) : path.lineTo(p.dx, p.dy);
      }
      canvas.drawPath(
        path,
        Paint()
          ..color = active
          ..style = PaintingStyle.stroke
          ..strokeWidth = 8
          ..strokeCap = StrokeCap.round
          ..strokeJoin = StrokeJoin.round,
      );
    }

    // Marcas cada 1/12 del recorrido; las principales más largas.
    final tick = Paint()
      ..color = scheme.outlineVariant
      ..strokeCap = StrokeCap.round;
    const ticks = 24;
    for (var i = 0; i <= ticks; i++) {
      if (wrap && i == ticks) break;
      final t = _start + _sweep * i / ticks;
      final major = i % 6 == 0;
      final dir = Offset(math.cos(t), math.sin(t));
      final r0 = trackR - 18;
      final r1 = r0 - (major ? 10 : 5);
      canvas.drawLine(
        c + dir * r0,
        c + dir * r1,
        tick..strokeWidth = major ? 3 : 2,
      );
    }

    // Cuerpo de galleta: gira con el valor y se encoge un poco al presionar.
    final bodyR = (trackR - 38) * (1 - 0.04 * press);
    final rot = _toAngle(value);
    final cookie = _cookie(c, bodyR, lobes: 9, depth: 0.07, rotation: rot);
    canvas.drawShadow(cookie, scheme.shadow, 4 + 4 * press, false);
    canvas.drawPath(
      cookie,
      Paint()
        ..color = enabled
            ? scheme.primaryContainer
            : scheme.surfaceContainerHigh,
    );

    // Indicador: pastilla en el borde de la galleta apuntando al valor.
    final dir = Offset(math.cos(rot), math.sin(rot));
    final dotC = c + dir * (bodyR - 20);
    canvas.drawCircle(dotC, 9 + 2 * press, Paint()..color = active);

    // Posición real del motor: anillo del color terciario sobre la pista.
    // Fuera de rango se queda en el tope; en modo circular, módulo 360.
    final m = measured;
    if (m != null) {
      final mv = wrap ? min + (m - min) % (max - min) : m.clamp(min, max);
      final ma = _toAngle(mv);
      final mp = c + Offset(math.cos(ma), math.sin(ma)) * trackR;
      canvas.drawCircle(mp, 9, Paint()..color = scheme.tertiary);
      canvas.drawCircle(mp, 5, Paint()..color = scheme.onTertiary);
    }

    // Punta en la pista exterior que marca el valor exacto.
    final head = c + Offset(math.cos(a1), math.sin(a1)) * trackR;
    canvas.drawCircle(head, 10, Paint()..color = active);
    canvas.drawCircle(
      head,
      4,
      Paint()..color = enabled ? scheme.onPrimary : scheme.surface,
    );
  }

  /// Círculo con [lobes] ondulaciones suaves en el borde.
  static Path _cookie(
    Offset c,
    double r, {
    required int lobes,
    required double depth,
    double rotation = 0,
  }) {
    final path = Path();
    const steps = 180;
    for (var i = 0; i <= steps; i++) {
      final t = 2 * math.pi * i / steps;
      final rr =
          r * (1 - depth / 2 + depth / 2 * math.cos(lobes * (t - rotation)));
      final p = c + Offset(math.cos(t), math.sin(t)) * rr;
      i == 0 ? path.moveTo(p.dx, p.dy) : path.lineTo(p.dx, p.dy);
    }
    return path..close();
  }

  @override
  bool shouldRepaint(_KnobPainter old) =>
      old.value != value ||
      old.press != press ||
      old.min != min ||
      old.max != max ||
      old.wrap != wrap ||
      old.sweepDegrees != sweepDegrees ||
      old.measured != measured ||
      old.enabled != enabled ||
      old.scheme != scheme;
}
