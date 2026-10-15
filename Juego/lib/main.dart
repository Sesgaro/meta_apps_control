import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter/material.dart';

import 'platform.dart';
import 'theme/expressive_theme.dart';
import 'ui/game_page.dart';

void main() => runApp(const GatoApp());

class GatoApp extends StatelessWidget {
  const GatoApp({super.key});

  @override
  Widget build(BuildContext context) {
    // En el Quest, siempre el rosa por defecto.
    if (isQuest) return _app(null, null);
    // Paleta del fondo de pantalla en Android 12+; si no hay, el rosa.
    return DynamicColorBuilder(
      builder: (lightDynamic, darkDynamic) =>
          _app(lightDynamic?.harmonized(), darkDynamic?.harmonized()),
    );
  }

  Widget _app(ColorScheme? light, ColorScheme? dark) => MaterialApp(
    title: 'Gato cartesiano',
    debugShowCheckedModeBanner: false,
    theme: buildExpressiveTheme(
      brightness: Brightness.light,
      dynamicScheme: light,
    ),
    darkTheme: buildExpressiveTheme(
      brightness: Brightness.dark,
      dynamicScheme: dark,
    ),
    home: const GamePage(),
  );
}
