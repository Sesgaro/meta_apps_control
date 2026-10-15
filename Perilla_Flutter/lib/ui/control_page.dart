import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../ble/angle_link.dart';
import '../panels/panel_bus.dart';
import '../platform.dart';
import '../theme/expressive_theme.dart';
import 'angle_range.dart';
import 'widgets/device_sheet.dart';
import 'widgets/knob_card.dart';

class ControlPage extends StatefulWidget {
  const ControlPage({super.key});

  @override
  State<ControlPage> createState() => _ControlPageState();
}

class _ControlPageState extends State<ControlPage> {
  final _link = AngleLink();
  final _number = TextEditingController();
  final _numberFocus = FocusNode();

  AngleRange _range = AngleRange.servo;
  double _angle = 90;

  /// Encendido: se manda mientras se gira. Apagado: solo al soltar.
  bool _live = true;

  @override
  void initState() {
    super.initState();
    _link.addListener(_followEsp);
    if (isQuest) {
      PanelBus.listen(_onPanelMessage);
      // La posición real llega por BLE aquí; la perilla también la muestra.
      _link.addListener(_publishState);
      // La perilla vive en su propio panel: se abre junto con este.
      WidgetsBinding.instance.addPostFrameCallback((_) => _openKnob());
    }
  }

  /// Quest: perilla 3D (inmersiva, se gira con la mano) en vez del panel 2D.
  bool _knob3d = false;

  void _openKnob() =>
      PanelBus.send(_knob3d ? PanelBus.openKnob3d : PanelBus.openKnob);

  /// Toques recientes sobre el título, para el gesto secreto.
  final _secretTaps = <DateTime>[];

  void _secretTap() {
    final now = DateTime.now();
    _secretTaps
      ..add(now)
      ..removeWhere((t) => now.difference(t) > const Duration(seconds: 2));
    if (_secretTaps.length < 5) return;
    _secretTaps.clear();
    HapticFeedback.heavyImpact();
    PanelBus.send(PanelBus.openCanvas);
  }

  void _onPanelMessage(String method, Map args) {
    switch (method) {
      case PanelBus.hello:
        _publishState();
      case PanelBus.knobMode:
        // La perilla 3D pidió volver a las ventanas 2D.
        setState(() => _knob3d = args['mode'] == '3d');
        _openKnob();
      case PanelBus.set:
        final value = (args['value'] as num).toDouble();
        final isFinal = args['final'] == true;
        _set(value, send: isFinal || _live);
    }
  }

  /// Avisa al panel de la perilla del ángulo y rango actuales.
  void _publishState() {
    if (!isQuest) return;
    PanelBus.send(PanelBus.state, {
      'angle': _angle,
      'range': _range.name,
      'measured': _link.motor?.position,
      'connected': _link.isConnected,
    });
  }

  @override
  void dispose() {
    _link.dispose();
    _number.dispose();
    _numberFocus.dispose();
    super.dispose();
  }

  Future<void> _pickDevice() async {
    final device = await showDeviceSheet(context);
    if (device == null) return;
    await _link.connect(device);
    if (!_link.isConnected) return;
    if (_link.hasState) {
      // La ESP ya tiene una referencia (quizá la puso otro dispositivo): la
      // perilla la adopta en vez de imponer la suya. Ver [_followEsp].
      _lastLocal = DateTime(0);
      _modeOffered = false;
    } else {
      // Firmware sin estado: no se sabe dónde está, se le manda la perilla.
      _link.send(_angle);
    }
  }

  /// Cambia el modo de rango, lo comparte con los demás dispositivos (vía la
  /// ESP) y recorta el ángulo al nuevo rango.
  void _setRange(AngleRange r) {
    setState(() => _range = r);
    _link.sendMode(r.index);
    _set(_angle);
  }

  /// Momento del último cambio hecho en esta app (perilla, botones, campo).
  DateTime _lastLocal = DateTime(0);

  /// Ya se le dio el modo a una ESP que no tenía (recién encendida).
  bool _modeOffered = false;

  /// Hay un valor local sin mandar (envío en vivo apagado, girando).
  bool _unsent = false;

  /// La perilla sigue a la referencia que reporta la ESP, así refleja lo que
  /// mande el otro dispositivo o el puerto serie. Justo después de un cambio
  /// local la ESP aún reporta el valor anterior (el estado llega cada
  /// 100 ms), por eso se espera un momento antes de hacerle caso.
  void _followEsp() {
    final m = _link.motor;
    if (m == null || _unsent) return;
    if (m.mode == null && !_modeOffered) {
      // ESP sin modo: esta app es la primera, le da el suyo.
      _modeOffered = true;
      _link.sendMode(_range.index);
    }
    if (DateTime.now().difference(_lastLocal) < _echoGrace) return;
    // Otro dispositivo cambió el modo: se adopta sin reenviar nada.
    final mode = m.mode;
    final range = mode != null && mode < AngleRange.values.length
        ? AngleRange.values[mode]
        : _range;
    final remote = range.clamp(m.reference);
    if (range == _range && (remote - _angle).abs() < 0.05) return;
    setState(() {
      _range = range;
      _angle = remote;
    });
    _publishState();
  }

  /// Fija el ángulo desde cualquier control y lo manda.
  void _set(double v, {bool send = true}) {
    final next = _range.clamp(v);
    setState(() => _angle = next);
    _lastLocal = DateTime.now();
    _unsent = !send;
    if (send) _link.send(next);
    _publishState();
  }

  void _submitNumber() {
    final parsed = double.tryParse(_number.text.replaceAll(',', '.'));
    if (parsed == null) return;
    HapticFeedback.lightImpact();
    _set(parsed);
    _number.clear();
    _numberFocus.unfocus();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      // En el Quest no hay fondo: las tarjetas flotan sobre el passthrough.
      backgroundColor: isQuest ? Colors.transparent : null,
      body: SafeArea(
        child: ListenableBuilder(
          listenable: _link,
          builder: (context, _) => isQuest
              ? _buildQuest()
              : LayoutBuilder(
                  builder: (context, box) => box.maxWidth >= _wideBreakpoint
                      ? _buildWide(box)
                      : _buildNarrow(),
                ),
        ),
      ),
    );
  }

  /// Teléfono en vertical: una sola columna desplazable.
  Widget _buildNarrow() {
    return CustomScrollView(
      slivers: [
        const SliverAppBar.large(title: Text('Control de ángulos')),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 32),
          sliver: SliverList.list(
            children: [
              _connection(),
              const SizedBox(height: 16),
              _knob(),
              const SizedBox(height: 16),
              ..._controls(),
            ],
          ),
        ),
      ],
    );
  }

  /// Panel apaisado (Meta Quest, tablet): perilla a la izquierda, ajustes a
  /// la derecha, todo a la vista sin desplazar la perilla.
  Widget _buildWide(BoxConstraints box) {
    // La perilla es cuadrada; que quepa a lo alto junto a los botones finos.
    final knobSide = (box.maxHeight - 32 - 96).clamp(240.0, 620.0);
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: knobSide + 32,
            child: Center(child: SingleChildScrollView(child: _knob())),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: ListView(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(4, 4, 4, 16),
                  child: Text(
                    'Control de ángulos',
                    style: Theme.of(context).textTheme.headlineMedium,
                  ),
                ),
                _connection(),
                const SizedBox(height: 16),
                ..._controls(),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// Panel de opciones del Quest: todo menos la perilla, que tiene su propio
  /// panel. Sin fondo; cada bloque es una tarjeta suelta.
  Widget _buildQuest() {
    final scheme = Theme.of(context).colorScheme;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        // El título va en su propia pastilla para leerse sobre el entorno.
        Align(
          alignment: Alignment.centerLeft,
          // Gesto secreto: 5 toques rápidos abren el lienzo de dibujo.
          child: GestureDetector(
            onTap: _secretTap,
            child: Material(
              color: scheme.primaryContainer,
              borderRadius: ExpressiveShapes.pill,
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 24,
                  vertical: 12,
                ),
                child: Text(
                  'Control de ángulos',
                  style: Theme.of(context).textTheme.headlineSmall
                      ?.copyWith(color: scheme.onPrimaryContainer),
                ),
              ),
            ),
          ),
        ),
        const SizedBox(height: 16),
        _connection(),
        const SizedBox(height: 16),
        ..._controls(),
      ],
    );
  }

  /// Conexión y, debajo, lo que reporta la ESP mientras haya enlace.
  Widget _connection() => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _ConnectionCard(
        link: _link,
        onConnect: _pickDevice,
        onDisconnect: _link.disconnect,
      ),
      AnimatedSize(
        duration: ExpressiveMotion.normal,
        curve: ExpressiveMotion.effects,
        alignment: Alignment.topCenter,
        child: _link.isConnected
            ? Padding(
                padding: const EdgeInsets.only(top: 16),
                child: _MotorCard(link: _link),
              )
            : const SizedBox(width: double.infinity),
      ),
    ],
  );

  Widget _knob() => KnobCard(
    angle: _angle,
    range: _range,
    onChanged: (v) => _set(v, send: _live),
    onChangeEnd: (v) => _set(v),
    onStep: (d) => _set(_angle + d),
    measured: _link.motor?.position,
  );

  List<Widget> _controls() {
    final scheme = Theme.of(context).colorScheme;
    return [
      _Section(
        title: 'Valor exacto',
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: TextField(
                controller: _number,
                focusNode: _numberFocus,
                keyboardType: const TextInputType.numberWithOptions(
                  decimal: true,
                  signed: true,
                ),
                inputFormatters: [
                  // Hasta 3 enteros y 1 decimal (la trama va en décimas).
                  TextInputFormatter.withFunction(
                    (old, next) =>
                        _numberPattern.hasMatch(next.text) ? next : old,
                  ),
                ],
                textInputAction: TextInputAction.send,
                onSubmitted: (_) => _submitNumber(),
                decoration: InputDecoration(
                  hintText: '${_range.min.toInt()} a ${_range.max.toInt()}',
                  suffixText: '°',
                  filled: true,
                  fillColor: scheme.surfaceContainerHighest,
                  border: const OutlineInputBorder(
                    borderRadius: ExpressiveShapes.large,
                    borderSide: BorderSide.none,
                  ),
                ),
              ),
            ),
            const SizedBox(width: 12),
            FilledButton.icon(
              onPressed: _submitNumber,
              icon: const Icon(Icons.send_rounded),
              label: const Text('Enviar'),
            ),
          ],
        ),
      ),
      const SizedBox(height: 16),
      _Section(
        title: 'Atajos',
        child: Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final p in _presets(_range))
              ActionChip(
                label: Text('${p.toInt()}°'),
                onPressed: () {
                  HapticFeedback.selectionClick();
                  _set(p);
                },
              ),
          ],
        ),
      ),
      const SizedBox(height: 16),
      _Section(
        title: 'Ajustes',
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SegmentedButton<AngleRange>(
              segments: [
                for (final r in AngleRange.values)
                  ButtonSegment(value: r, label: Text(r.label)),
              ],
              selected: {_range},
              showSelectedIcon: false,
              onSelectionChanged: (s) => _setRange(s.first),
            ),
            const SizedBox(height: 8),
            SwitchListTile(
              contentPadding: const EdgeInsets.symmetric(horizontal: 4),
              title: const Text('Envío en vivo'),
              subtitle: Text(
                _live
                    ? 'Se manda mientras giras la perilla'
                    : 'Se manda solo al soltar la perilla',
              ),
              value: _live,
              onChanged: (v) => setState(() => _live = v),
            ),
            if (isQuest) ...[
              const SizedBox(height: 8),
              SegmentedButton<bool>(
                segments: const [
                  ButtonSegment(
                    value: false,
                    icon: Icon(Icons.crop_square_rounded),
                    label: Text('Perilla 2D'),
                  ),
                  ButtonSegment(
                    value: true,
                    icon: Icon(Icons.view_in_ar_rounded),
                    label: Text('Perilla 3D'),
                  ),
                ],
                selected: {_knob3d},
                onSelectionChanged: (s) {
                  setState(() => _knob3d = s.first);
                  // Una sola perilla a la vez: se cierra la otra.
                  PanelBus.send(
                    _knob3d ? PanelBus.closeKnob : PanelBus.closeKnob3d,
                  );
                  _openKnob();
                },
              ),
              const SizedBox(height: 8),
              FilledButton.tonalIcon(
                onPressed: _openKnob,
                icon: const Icon(Icons.open_in_new_rounded),
                label: const Text('Mostrar perilla'),
              ),
            ],
          ],
        ),
      ),
    ];
  }

  static List<double> _presets(AngleRange r) => switch (r) {
    AngleRange.servo => [0, 45, 90, 135, 180],
    AngleRange.full => [0, 90, 180, 270],
    AngleRange.signed => [-90, -45, 0, 45, 90],
  };
}

class _ConnectionCard extends StatelessWidget {
  const _ConnectionCard({
    required this.link,
    required this.onConnect,
    required this.onDisconnect,
  });

  final AngleLink link;
  final VoidCallback onConnect;
  final VoidCallback onDisconnect;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;

    final (bg, fg, icon, title) = switch (link.status) {
      LinkStatus.connected => (
        scheme.tertiaryContainer,
        scheme.onTertiaryContainer,
        Icons.bluetooth_connected_rounded,
        link.deviceName,
      ),
      LinkStatus.connecting => (
        scheme.secondaryContainer,
        scheme.onSecondaryContainer,
        Icons.bluetooth_searching_rounded,
        'Conectando…',
      ),
      LinkStatus.disconnected => (
        scheme.surfaceContainerHigh,
        scheme.onSurface,
        Icons.bluetooth_disabled_rounded,
        'Sin conexión',
      ),
    };

    final subtitle = switch (link.status) {
      LinkStatus.connected =>
        link.lastSent == null
            ? 'Listo para enviar'
            : 'Último enviado: ${_fmt(link.lastSent!)}°',
      LinkStatus.connecting => link.deviceName,
      LinkStatus.disconnected => link.error ?? 'Busca tu ESP32-S3 para empezar',
    };

    return AnimatedContainer(
      duration: ExpressiveMotion.normal,
      curve: ExpressiveMotion.effects,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: ExpressiveShapes.asymmetric,
      ),
      child: Row(
        children: [
          AnimatedSwitcher(
            duration: ExpressiveMotion.normal,
            transitionBuilder: (child, a) => ScaleTransition(
              scale: CurvedAnimation(
                parent: a,
                curve: ExpressiveMotion.spatial,
              ),
              child: child,
            ),
            child: link.status == LinkStatus.connecting
                ? SizedBox.square(
                    key: const ValueKey('spin'),
                    dimension: 32,
                    child: CircularProgressIndicator(strokeWidth: 3, color: fg),
                  )
                : Icon(icon, key: ValueKey(icon), size: 32, color: fg),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: text.titleLarge?.copyWith(color: fg)),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: text.bodyMedium?.copyWith(
                    color:
                        link.status == LinkStatus.disconnected &&
                            link.error != null
                        ? scheme.error
                        : fg.withValues(alpha: 0.8),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          switch (link.status) {
            LinkStatus.connected => OutlinedButton(
              onPressed: onDisconnect,
              child: const Text('Salir'),
            ),
            LinkStatus.connecting => const SizedBox.shrink(),
            LinkStatus.disconnected => FilledButton(
              onPressed: onConnect,
              child: const Text('Conectar'),
            ),
          },
        ],
      ),
    );
  }
}

/// Lectura en vivo del servidor GATT de la ESP: posición medida con el
/// encoder, referencia que tiene la ESP, rpm y salida PWM.
class _MotorCard extends StatelessWidget {
  const _MotorCard({required this.link});

  final AngleLink link;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final m = link.motor;

    return _Section(
      title: 'Lectura de la ESP',
      child: !link.hasState
          ? Text(
              'El firmware no publica su estado. Sube la versión con la '
              'característica de estado (7a1d0002).',
              style: text.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
            )
          : m == null
          ? Text(
              'Esperando datos…',
              style: text.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
            )
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.baseline,
                  textBaseline: TextBaseline.alphabetic,
                  children: [
                    Text(
                      '${_fmt(m.position)}°',
                      style: text.displaySmall?.copyWith(
                        color: scheme.tertiary,
                        fontFeatures: const [FontFeature.tabularFigures()],
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(
                      'posición real',
                      style: text.labelLarge?.copyWith(
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    _Reading('Referencia', '${_fmt(m.reference)}°'),
                    _Reading('Error', '${_fmt(m.reference - m.position)}°'),
                    _Reading('Velocidad', '${_fmt(m.rpm)} rpm'),
                    _Reading('PWM', '${m.pwm}'),
                  ],
                ),
              ],
            ),
    );
  }
}

class _Reading extends StatelessWidget {
  const _Reading(this.label, this.value);
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
      decoration: BoxDecoration(
        color: scheme.tertiaryContainer,
        borderRadius: ExpressiveShapes.medium,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: text.labelSmall?.copyWith(
              color: scheme.onTertiaryContainer.withValues(alpha: 0.8),
            ),
          ),
          Text(
            value,
            style: text.titleMedium?.copyWith(
              color: scheme.onTertiaryContainer,
              fontFeatures: const [FontFeature.tabularFigures()],
            ),
          ),
        ],
      ),
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.child});
  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    // Material y no Container: los ListTile de dentro pintan su ripple en el
    // Material más cercano, y un fondo intermedio lo taparía.
    return Material(
      color: scheme.surfaceContainerLow,
      borderRadius: ExpressiveShapes.large,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                color: scheme.primary,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 12),
            child,
          ],
        ),
      ),
    );
  }
}

/// Tras un cambio local, cuánto se ignora la referencia que reporta la ESP.
const _echoGrace = Duration(milliseconds: 600);

/// Ancho a partir del cual se usan dos columnas (panel de Quest, tablet).
const _wideBreakpoint = 840.0;

final _numberPattern = RegExp(r'^-?\d{0,3}([.,]\d?)?$');

String _fmt(double v) {
  final r = (v * 10).round() / 10;
  return r == r.roundToDouble() ? r.toInt().toString() : r.toStringAsFixed(1);
}
