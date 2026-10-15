import 'package:flutter/material.dart';

import '../panels/panel_bus.dart';
import 'angle_range.dart';
import 'widgets/knob_card.dart';

/// Panel independiente de la perilla (Quest). No conecta nada: propone
/// valores al panel de opciones, que es quien los manda por BLE y devuelve
/// el estado confirmado.
class KnobPanel extends StatefulWidget {
  const KnobPanel({super.key});

  @override
  State<KnobPanel> createState() => _KnobPanelState();
}

class _KnobPanelState extends State<KnobPanel> {
  AngleRange _range = AngleRange.servo;
  double _angle = 90;
  double? _measured;

  @override
  void initState() {
    super.initState();
    PanelBus.listen((method, args) {
      if (method != PanelBus.state) return;
      setState(() {
        _angle = (args['angle'] as num).toDouble();
        _range = AngleRange.values.byName(args['range'] as String);
        _measured = (args['measured'] as num?)?.toDouble();
      });
    });
    PanelBus.send(PanelBus.hello);
  }

  void _propose(double v, {required bool isFinal}) {
    // Se pinta ya, sin esperar la ida y vuelta al otro panel.
    setState(() => _angle = _range.clamp(v));
    PanelBus.send(PanelBus.set, {'value': v, 'final': isFinal});
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 640),
              child: KnobCard(
                angle: _angle,
                range: _range,
                onChanged: (v) => _propose(v, isFinal: false),
                onChangeEnd: (v) => _propose(v, isFinal: true),
                onStep: (d) => _propose(_angle + d, isFinal: true),
                measured: _measured,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
