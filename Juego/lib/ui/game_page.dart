import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../ble/game_link.dart';
import '../ble/game_protocol.dart';
import '../panels/panel_bus.dart';
import '../platform.dart';
import '../theme/expressive_theme.dart';
import 'widgets/board_view.dart';
import 'widgets/device_sheet.dart';

/// Pantalla del gato. En el Quest: conexión, elección de ficha y estado; se
/// juega solo en el tablero 3D (GameActivity3D), al que alimenta por
/// [PanelBus]: le manda el estado y recibe los movimientos de la cabeza.
/// En el teléfono es solo espectador: ve el tablero 2D pero no interviene.
class GamePage extends StatefulWidget {
  const GamePage({super.key});

  @override
  State<GamePage> createState() => _GamePageState();
}

/// Cómo se juega en el tablero 3D. El índice viaja al 3D en `mode`.
enum PlayMode {
  /// Resortera: lanzas una pelota; la cabeza va a donde caería.
  slingshot('Resortera', 'Jala la pelota de la resortera y suéltala.'),

  /// Máquina de peluches: joystick mueve la cabeza y el botón suelta la garra.
  claw('Grúa', 'Mueve la cabeza con el joystick y aprieta el botón.');

  const PlayMode(this.label, this.hint);
  final String label;
  final String hint;
}

class _GamePageState extends State<GamePage> {
  final _link = GameLink();

  /// Con qué ficha juega este visor/teléfono.
  Player _me = Player.x;
  PlayMode _mode = PlayMode.slingshot;

  // Marcador de la sesión. [_lastPhase] null = aún no se ve ninguna partida,
  // así no se cuenta una que ya venía terminada al conectar.
  final _wins = {Player.x: 0, Player.o: 0};
  int _draws = 0;
  Phase? _lastPhase;

  void _countResult() {
    final s = _link.state;
    if (s != null &&
        s.phase == Phase.over &&
        _lastPhase != null &&
        _lastPhase != Phase.over) {
      if (s.isDraw) {
        _draws++;
      } else {
        _wins[s.winnerPlayer] = (_wins[s.winnerPlayer] ?? 0) + 1;
      }
    }
    _lastPhase = s?.phase;
  }

  String get _scoreText =>
      'X ${_wins[Player.x]} · O ${_wins[Player.o]} · empates $_draws';

  void _savePrefs() =>
      PanelBus.savePrefs({'me': _me.code, 'mode': _mode.index});

  @override
  void initState() {
    super.initState();
    _link.addListener(_countResult);
    if (isQuest) {
      PanelBus.loadPrefs().then((p) {
        if (!mounted) return;
        final mode = p['mode'];
        setState(() {
          _me = p['me'] == Player.o.code ? Player.o : Player.x;
          if (mode is int && mode >= 0 && mode < PlayMode.values.length) {
            _mode = PlayMode.values[mode];
          }
        });
        _publishState();
      });
      PanelBus.listen(_onPanelMessage);
      _link.addListener(_publishState);
      _link.addListener(_open3dOnConnect);
      _link.onPose = (f) => PanelBus.send(PanelBus.poses, {'data': f});
    }
  }

  bool _wasConnected = false;

  /// En el Quest se juega en 3D: al conectar (o simular) se abre solo.
  void _open3dOnConnect() {
    if (_link.isConnected && !_wasConnected) PanelBus.send(PanelBus.openGame3d);
    _wasConnected = _link.isConnected;
  }

  @override
  void dispose() {
    _link.dispose();
    super.dispose();
  }

  bool get _canMove {
    final s = _link.state;
    return isQuest &&
        _link.isConnected &&
        s != null &&
        s.phase == Phase.turn &&
        (s.turn == _me || _link.isDemo);
  }

  // En la simulación un solo dispositivo juega con las dos fichas.
  Player get _player => _link.isDemo ? _link.state!.turn : _me;

  void _move(double x, double y) {
    if (_canMove) _link.move(_player, x, y);
  }

  void _fire() {
    if (_canMove) _link.fire(_player);
  }

  Future<void> _pickDevice() async {
    final device = await showDeviceSheet(context);
    if (device == null) return;
    await _link.connect(device);
  }

  // --- Tablero 3D (Quest) ---------------------------------------------------

  void _onPanelMessage(String method, Map args) {
    switch (method) {
      case PanelBus.hello:
        _publishState();
      case PanelBus.move:
        _move((args['x'] as num).toDouble(), (args['y'] as num).toDouble());
      case PanelBus.fire:
        _fire();
      case PanelBus.newGame:
        _link.newGame();
      case PanelBus.pose:
        if (args['data'] case final Uint8List data) _link.sendPose(data);
    }
  }

  void _publishState() {
    if (!isQuest) return;
    PanelBus.send(PanelBus.game, {
      ...?_link.state?.toMap(),
      'connected': _link.isConnected,
      // En la simulación el 3D deja mover al que tenga el turno.
      'me': (_link.isDemo ? _link.state?.turn ?? _me : _me).code,
      'mode': _mode.index,
      'status': _statusText(_link.state),
      'score': _scoreText,
    });
  }

  // --- Textos ----------------------------------------------------------------

  String _who(Player p) => isQuest && p == _me ? '${p.symbol} (tú)' : p.symbol;

  String _statusText(GameState? s) {
    if (!_link.isConnected || s == null) return 'Sin conexión';
    return switch (s.phase) {
      Phase.waiting => 'Pulsa «Nuevo juego» para empezar',
      Phase.turn =>
        'Turno de ${_who(s.turn)} · ${s.timeLeft.ceil()} s'
            '${s.turn == _me ? '' : ' — espera'}',
      Phase.resolving => 'Tiempo: leyendo los sensores…',
      Phase.over => s.isDraw ? 'Empate' : '¡Gana ${_who(s.winnerPlayer)}!',
    };
  }

  String? _lastShotText(GameState s) {
    final who = _who(s.lastPlayer);
    final cell = s.lastCell == null ? '' : ' (casilla ${s.lastCell! + 1})';
    return switch (s.lastResult) {
      ShotResult.none => null,
      ShotResult.placed => '$who puso su ficha$cell',
      ShotResult.missed => '$who no le atinó a ninguna casilla',
      ShotResult.occupied => '$who le atinó a una casilla ocupada$cell',
    };
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      // En el Quest no hay fondo: las tarjetas flotan sobre el passthrough.
      backgroundColor: isQuest ? Colors.transparent : null,
      body: SafeArea(
        child: ListenableBuilder(
          listenable: _link,
          builder: (context, _) => Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 640),
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  _Title(),
                  const SizedBox(height: 16),
                  _ConnectionCard(
                    link: _link,
                    onConnect: _pickDevice,
                    onDemo: isQuest ? _link.startDemo : null,
                    onDisconnect: _link.disconnect,
                  ),
                  if (isQuest) ...[
                    const SizedBox(height: 16),
                    _Section(
                      title: 'Tu ficha',
                      child: SegmentedButton<Player>(
                        segments: const [
                          ButtonSegment(
                            value: Player.x,
                            label: Text('Jugador X'),
                          ),
                          ButtonSegment(
                            value: Player.o,
                            label: Text('Jugador O'),
                          ),
                        ],
                        selected: {_me},
                        showSelectedIcon: false,
                        onSelectionChanged: (s) {
                          setState(() => _me = s.first);
                          _savePrefs();
                          _publishState();
                        },
                      ),
                    ),
                    const SizedBox(height: 16),
                    _Section(
                      title: 'Modo de juego',
                      child: SegmentedButton<PlayMode>(
                        segments: [
                          for (final m in PlayMode.values)
                            ButtonSegment(value: m, label: Text(m.label)),
                        ],
                        selected: {_mode},
                        showSelectedIcon: false,
                        onSelectionChanged: (s) {
                          setState(() => _mode = s.first);
                          _savePrefs();
                          _publishState();
                        },
                      ),
                    ),
                  ],
                  const SizedBox(height: 16),
                  ..._game(context),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  List<Widget> _game(BuildContext context) {
    final s = _link.state;
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    if (!_link.isConnected || s == null) return const [];

    final last = _lastShotText(s);
    return [
      _Section(
        title: 'Partida',
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(_statusText(s), style: text.headlineSmall),
            const SizedBox(height: 4),
            Text(
              'Marcador: $_scoreText',
              style: text.titleMedium?.copyWith(color: scheme.primary),
            ),
            if (s.phase == Phase.turn) ...[
              const SizedBox(height: 8),
              LinearProgressIndicator(
                value: s.turnSeconds == 0 ? 0 : s.timeLeft / s.turnSeconds,
                borderRadius: ExpressiveShapes.pill,
              ),
            ],
            if (last != null) ...[
              const SizedBox(height: 8),
              Text(
                last,
                style: text.bodyLarge?.copyWith(color: scheme.onSurfaceVariant),
              ),
            ],
            if (!s.yAxisLinked) ...[
              const SizedBox(height: 8),
              Text(
                'La ESP32 del eje X no responde por la UART: el eje X no '
                'funciona.',
                style: text.bodyMedium?.copyWith(color: scheme.error),
              ),
            ],
            if (!isQuest) ...[
              const SizedBox(height: 16),
              BoardView(state: s),
              const SizedBox(height: 8),
              Text(
                'Solo espectador: se juega desde los Quest.',
                style: text.bodyMedium?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ],
            if (isQuest) ...[
              const SizedBox(height: 8),
              Text(
                'Juega en el tablero 3D. ${_mode.hint}',
                style: text.bodyMedium?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 16),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  FilledButton.icon(
                    onPressed: () {
                      HapticFeedback.mediumImpact();
                      _link.newGame();
                    },
                    icon: const Icon(Icons.restart_alt_rounded),
                    label: const Text('Nuevo juego'),
                  ),
                  FilledButton.tonalIcon(
                    onPressed: () => PanelBus.send(PanelBus.openGame3d),
                    icon: const Icon(Icons.view_in_ar_rounded),
                    label: const Text('Abrir tablero 3D'),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    ];
  }
}

class _Title extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Align(
      alignment: Alignment.centerLeft,
      child: Material(
        color: scheme.primaryContainer,
        borderRadius: ExpressiveShapes.pill,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
          child: Text(
            'Gato cartesiano',
            style: Theme.of(context).textTheme.headlineSmall
                ?.copyWith(color: scheme.onPrimaryContainer),
          ),
        ),
      ),
    );
  }
}

class _ConnectionCard extends StatelessWidget {
  const _ConnectionCard({
    required this.link,
    required this.onConnect,
    this.onDemo,
    required this.onDisconnect,
  });

  final GameLink link;
  final VoidCallback onConnect;
  final VoidCallback? onDemo;
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
        link.isDemo
            ? 'Sin ESP: juegas con las dos fichas'
            : 'Conectado a la máquina',
      LinkStatus.connecting => link.deviceName,
      LinkStatus.disconnected =>
        link.error ?? 'Busca ${GameProtocol.deviceName} para empezar',
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
          link.status == LinkStatus.connecting
              ? SizedBox.square(
                  dimension: 32,
                  child: CircularProgressIndicator(strokeWidth: 3, color: fg),
                )
              : Icon(icon, size: 32, color: fg),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: text.titleLarge?.copyWith(color: fg)),
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
            LinkStatus.disconnected => Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                FilledButton(
                  onPressed: onConnect,
                  child: const Text('Conectar'),
                ),
                if (onDemo != null) ...[
                  const SizedBox(height: 4),
                  TextButton(
                    onPressed: onDemo,
                    child: const Text('Probar sin ESP'),
                  ),
                ],
              ],
            ),
          },
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
