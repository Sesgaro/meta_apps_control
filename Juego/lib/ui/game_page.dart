import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../ble/game_link.dart';
import '../ble/game_protocol.dart';
import '../panels/panel_bus.dart';
import '../platform.dart';
import '../theme/expressive_theme.dart';
import 'widgets/board_view.dart';
import 'widgets/device_sheet.dart';

/// Pantalla del gato: conexión, elección de ficha, estado del juego y
/// tablero 2D. En el Quest además alimenta el tablero 3D (GameActivity3D)
/// por [PanelBus]: le manda el estado y recibe los movimientos de la cabeza.
class GamePage extends StatefulWidget {
  const GamePage({super.key});

  @override
  State<GamePage> createState() => _GamePageState();
}

class _GamePageState extends State<GamePage> {
  final _link = GameLink();

  /// Con qué ficha juega este visor/teléfono.
  Player _me = Player.x;

  @override
  void initState() {
    super.initState();
    if (isQuest) {
      PanelBus.listen(_onPanelMessage);
      _link.addListener(_publishState);
    }
  }

  @override
  void dispose() {
    _link.dispose();
    super.dispose();
  }

  bool get _canMove {
    final s = _link.state;
    return _link.isConnected &&
        s != null &&
        s.phase == Phase.turn &&
        s.turn == _me;
  }

  void _move(double x, double y) {
    if (_canMove) _link.move(_me, x, y);
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
      case PanelBus.newGame:
        _link.newGame();
    }
  }

  void _publishState() {
    if (!isQuest) return;
    PanelBus.send(PanelBus.game, {
      ...?_link.state?.toMap(),
      'connected': _link.isConnected,
      'me': _me.code,
      'status': _statusText(_link.state),
    });
  }

  // --- Textos ----------------------------------------------------------------

  String _who(Player p) => p == _me ? '${p.symbol} (tú)' : p.symbol;

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
                    onDisconnect: _link.disconnect,
                  ),
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
                        _publishState();
                      },
                    ),
                  ),
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
                'La ESP32 del eje Y no responde por la UART: el eje Y y los '
                'sensores IR no funcionan.',
                style: text.bodyMedium?.copyWith(color: scheme.error),
              ),
            ],
            const SizedBox(height: 16),
            BoardView(state: s, onMove: _canMove ? _move : null),
            const SizedBox(height: 8),
            Text(
              _canMove
                  ? 'Arrastra sobre el tablero para mover la cabeza.'
                  : 'Los motores están bloqueados para ti.',
              style: text.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
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
                if (isQuest)
                  FilledButton.tonalIcon(
                    onPressed: () => PanelBus.send(PanelBus.openGame3d),
                    icon: const Icon(Icons.view_in_ar_rounded),
                    label: const Text('Abrir tablero 3D'),
                  ),
              ],
            ),
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
    required this.onDisconnect,
  });

  final GameLink link;
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
      LinkStatus.connected => 'Conectado a la máquina',
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
