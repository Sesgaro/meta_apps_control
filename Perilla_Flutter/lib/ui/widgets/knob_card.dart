import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../theme/expressive_theme.dart';
import '../angle_range.dart';
import 'rotary_knob.dart';

/// Perilla con los botones de ajuste fino debajo, en su tarjeta.
class KnobCard extends StatelessWidget {
  const KnobCard({
    super.key,
    required this.angle,
    required this.range,
    required this.onChanged,
    required this.onChangeEnd,
    required this.onStep,
    this.measured,
  });

  final double angle;
  final AngleRange range;
  final ValueChanged<double> onChanged;
  final ValueChanged<double> onChangeEnd;
  final ValueChanged<double> onStep;

  /// Posición real reportada por la ESP, si la hay.
  final double? measured;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Theme.of(context).colorScheme.surfaceContainer,
      borderRadius: ExpressiveShapes.extraLarge,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8),
              child: RotaryKnob(
                value: angle,
                min: range.min,
                max: range.max,
                wrap: range.wrap,
                sweepDegrees: range.sweep,
                measured: measured,
                onChanged: onChanged,
                onChangeEnd: onChangeEnd,
              ),
            ),
            const SizedBox(height: 8),
            _FineAdjust(onStep: onStep),
          ],
        ),
      ),
    );
  }
}

/// Botones de ajuste fino, agrupados como un "button group" de M3E: los de
/// los extremos tienen la esquina exterior redondeada y la interior recta.
class _FineAdjust extends StatelessWidget {
  const _FineAdjust({required this.onStep});

  final ValueChanged<double> onStep;

  @override
  Widget build(BuildContext context) {
    const steps = [-10.0, -1.0, 1.0, 10.0];
    return Row(
      children: [
        for (var i = 0; i < steps.length; i++) ...[
          if (i > 0) const SizedBox(width: 4),
          Expanded(
            child: FilledButton.tonal(
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 16),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.horizontal(
                    left: Radius.circular(i == 0 ? 100 : 8),
                    right: Radius.circular(i == steps.length - 1 ? 100 : 8),
                  ),
                ),
              ),
              onPressed: () {
                HapticFeedback.selectionClick();
                onStep(steps[i]);
              },
              child: Text(
                steps[i] > 0 ? '+${steps[i].toInt()}' : '${steps[i].toInt()}',
              ),
            ),
          ),
        ],
      ],
    );
  }
}
