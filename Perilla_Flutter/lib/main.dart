import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter/material.dart';

import 'platform.dart';
import 'theme/expressive_theme.dart';
import 'ui/control_page.dart';
import 'ui/drawing_panel.dart';
import 'ui/knob_panel.dart';

void main() => runApp(const ControlApp(home: ControlPage()));

/// Punto de entrada del panel de la perilla en el Quest (`KnobActivity`).
@pragma('vm:entry-point')
void knobMain() => runApp(const ControlApp(home: KnobPanel()));

/// Lienzo de dibujo del Quest (`CanvasActivity`), abierto con el gesto secreto.
@pragma('vm:entry-point')
void canvasMain() => runApp(const ControlApp(home: DrawingPanel()));

class ControlApp extends StatelessWidget {
  const ControlApp({super.key, required this.home});

  final Widget home;

  @override
  Widget build(BuildContext context) {
    // En el Quest, siempre el rosa por defecto (la paleta del sistema allí
    // no aporta y el panel de la perilla corre sin plugins).
    if (isQuest) return _app(null, null);
    // Paleta del fondo de pantalla en Android 12+; si no hay, la variante
    // expresiva generada desde la semilla.
    return DynamicColorBuilder(
      builder: (lightDynamic, darkDynamic) =>
          _app(lightDynamic?.harmonized(), darkDynamic?.harmonized()),
    );
  }

  Widget _app(ColorScheme? light, ColorScheme? dark) => MaterialApp(
    title: 'Control de ángulos',
    debugShowCheckedModeBanner: false,
    theme: buildExpressiveTheme(
      brightness: Brightness.light,
      dynamicScheme: light,
    ),
    darkTheme: buildExpressiveTheme(
      brightness: Brightness.dark,
      dynamicScheme: dark,
    ),
    home: home,
  );
}
