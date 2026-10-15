import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';

import '../../ble/angle_protocol.dart';
import '../../theme/expressive_theme.dart';

/// Hoja inferior que escanea y devuelve el dispositivo elegido.
///
/// Por defecto filtra por el UUID de servicio del firmware; el interruptor
/// "Mostrar todos" sirve si se cambió el firmware y aún no anuncia el UUID.
Future<BluetoothDevice?> showDeviceSheet(BuildContext context) {
  return showModalBottomSheet<BluetoothDevice>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (_) => const _DeviceSheet(),
  );
}

class _DeviceSheet extends StatefulWidget {
  const _DeviceSheet();

  @override
  State<_DeviceSheet> createState() => _DeviceSheetState();
}

class _DeviceSheetState extends State<_DeviceSheet> {
  bool _showAll = false;
  String? _error;
  late final StreamSubscription<bool> _scanSub;
  bool _scanning = false;

  @override
  void initState() {
    super.initState();
    _scanSub = FlutterBluePlus.isScanning.listen((s) {
      if (mounted) setState(() => _scanning = s);
    });
    _scan();
  }

  @override
  void dispose() {
    _scanSub.cancel();
    FlutterBluePlus.stopScan();
    super.dispose();
  }

  Future<void> _scan() async {
    setState(() => _error = null);
    try {
      if (FlutterBluePlus.adapterStateNow != BluetoothAdapterState.on) {
        // En Android pide encender el Bluetooth con el diálogo del sistema.
        await FlutterBluePlus.turnOn();
      }
      await FlutterBluePlus.stopScan();
      await FlutterBluePlus.startScan(
        withServices: _showAll ? const [] : [AngleProtocol.serviceUuid],
        timeout: const Duration(seconds: 12),
      );
    } catch (e) {
      if (mounted) {
        setState(
          () => _error = e is FlutterBluePlusException ? e.description : '$e',
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;

    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.6,
      minChildSize: 0.4,
      maxChildSize: 0.9,
      builder: (context, scroll) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 0, 16, 8),
            child: Row(
              children: [
                Expanded(
                  child: Text('Dispositivos', style: text.headlineMedium),
                ),
                IconButton.filledTonal(
                  tooltip: _scanning ? 'Detener' : 'Buscar de nuevo',
                  onPressed: _scanning ? FlutterBluePlus.stopScan : _scan,
                  icon: Icon(
                    _scanning ? Icons.stop_rounded : Icons.refresh_rounded,
                  ),
                ),
              ],
            ),
          ),
          AnimatedSwitcher(
            duration: ExpressiveMotion.normal,
            child: _scanning
                ? const Padding(
                    key: ValueKey('scan'),
                    padding: EdgeInsets.symmetric(horizontal: 24),
                    child: LinearProgressIndicator(
                      borderRadius: ExpressiveShapes.pill,
                    ),
                  )
                : const SizedBox(key: ValueKey('idle'), height: 8),
          ),
          SwitchListTile(
            title: const Text('Mostrar todos'),
            subtitle: Text(
              'Sin filtrar por el servicio de ${AngleProtocol.deviceName}',
            ),
            value: _showAll,
            onChanged: (v) {
              setState(() => _showAll = v);
              _scan();
            },
          ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 8),
              child: Text(
                _error!,
                style: text.bodyMedium?.copyWith(color: scheme.error),
              ),
            ),
          Expanded(
            child: StreamBuilder<List<ScanResult>>(
              stream: FlutterBluePlus.scanResults,
              initialData: const [],
              builder: (context, snap) {
                final results = [...?snap.data]
                  ..sort((a, b) => b.rssi.compareTo(a.rssi));
                if (results.isEmpty) {
                  return Center(
                    child: Text(
                      _scanning ? 'Buscando…' : 'No se encontró nada',
                      style: text.bodyLarge?.copyWith(
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                  );
                }
                return ListView.separated(
                  controller: scroll,
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                  itemCount: results.length,
                  separatorBuilder: (_, _) => const SizedBox(height: 8),
                  itemBuilder: (context, i) => _ResultTile(
                    result: results[i],
                    onTap: () => Navigator.pop(context, results[i].device),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _ResultTile extends StatelessWidget {
  const _ResultTile({required this.result, required this.onTap});

  final ScanResult result;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final name = result.advertisementData.advName.isNotEmpty
        ? result.advertisementData.advName
        : result.device.platformName;
    final ours = result.advertisementData.serviceUuids.contains(
      AngleProtocol.serviceUuid,
    );

    return ListTile(
      tileColor: ours ? scheme.secondaryContainer : scheme.surfaceContainerHigh,
      leading: Icon(
        ours ? Icons.settings_input_antenna_rounded : Icons.bluetooth_rounded,
        color: ours ? scheme.onSecondaryContainer : scheme.onSurfaceVariant,
      ),
      title: Text(name.isEmpty ? 'Sin nombre' : name),
      subtitle: Text(result.device.remoteId.str),
      trailing: Text('${result.rssi} dBm'),
      onTap: onTap,
    );
  }
}
