/// Tema con filosofía Material 3 Expressive.
///
/// AVISO: Flutter 3.47.5 no implementa Material 3 Expressive. El propio SDK lo
/// dice en `page_transitions_theme.dart`:
///
///   "native Android is using Material 3 Expressive springs that are not
///    currently supported by Flutter. So for now at least, this is an
///    approximation."
///
/// Lo que SÍ es nativo y usamos aquí:
///   - `DynamicSchemeVariant`, el generador de paleta (aquí "vibrant", para
///     que el rosa por defecto no cambie de tono).
///   - `dynamic_color`, que lee la paleta del fondo de pantalla (Material You).
///
/// Lo que aproximamos a mano, porque no existe como componente:
///   - Formas: radios grandes y deliberadamente desiguales. En M3E la forma es
///     una herramienta expresiva de primer orden, no un detalle.
///   - Tipografía: más contraste de peso y tamaños mayores en los títulos.
///   - Movimiento: curvas con rebote que imitan los muelles de M3E.
library;

import 'package:flutter/material.dart';

/// Radios de esquina. M3E usa formas mucho más redondeadas y con más variedad
/// que M3 clásico; el contraste entre tamaños es intencional.
class ExpressiveShapes {
  ExpressiveShapes._();

  static const small = BorderRadius.all(Radius.circular(12));
  static const medium = BorderRadius.all(Radius.circular(20));
  static const large = BorderRadius.all(Radius.circular(28));
  static const extraLarge = BorderRadius.all(Radius.circular(36));

  /// Forma "de pastilla" para botones de acción principal.
  static const pill = BorderRadius.all(Radius.circular(100));

  /// Esquinas desiguales: el recurso más reconocible del lenguaje expresivo.
  static const asymmetric = BorderRadius.only(
    topLeft: Radius.circular(36),
    topRight: Radius.circular(12),
    bottomLeft: Radius.circular(12),
    bottomRight: Radius.circular(36),
  );
}

/// Curvas de movimiento. Flutter no trae los muelles de M3E, así que usamos
/// las curvas con sobreimpulso más parecidas.
class ExpressiveMotion {
  ExpressiveMotion._();

  static const spatial = Curves.easeOutBack;
  static const effects = Curves.easeOutCubic;

  static const fast = Duration(milliseconds: 180);
  static const normal = Duration(milliseconds: 350);
  static const slow = Duration(milliseconds: 550);
}

ThemeData buildExpressiveTheme({
  required Brightness brightness,
  ColorScheme? dynamicScheme,
}) {
  // Si el sistema nos da la paleta del fondo de pantalla (Android 12+), la
  // usamos tal cual. Si no, rosa por defecto. Variante "vibrant" y no
  // "expressive": la expresiva gira el tono de la semilla (rosa -> azul).
  final scheme =
      dynamicScheme ??
      ColorScheme.fromSeed(
        seedColor: const Color(0xFFE91E63),
        brightness: brightness,
        dynamicSchemeVariant: DynamicSchemeVariant.vibrant,
      );

  final base = ThemeData(colorScheme: scheme, useMaterial3: true);

  return base.copyWith(
    scaffoldBackgroundColor: scheme.surface,

    // Tipografía con más contraste de peso del que usa M3 clásico.
    textTheme: base.textTheme.copyWith(
      displayLarge: base.textTheme.displayLarge?.copyWith(
        fontWeight: FontWeight.w800,
        letterSpacing: -1.0,
      ),
      headlineLarge: base.textTheme.headlineLarge?.copyWith(
        fontWeight: FontWeight.w700,
        letterSpacing: -0.5,
      ),
      headlineMedium: base.textTheme.headlineMedium?.copyWith(
        fontWeight: FontWeight.w700,
      ),
      titleLarge: base.textTheme.titleLarge?.copyWith(
        fontWeight: FontWeight.w600,
      ),
      labelLarge: base.textTheme.labelLarge?.copyWith(
        fontWeight: FontWeight.w600,
        letterSpacing: 0.1,
      ),
    ),

    // Botones grandes y en pastilla: el área táctil generosa es parte del
    // lenguaje expresivo, no un capricho.
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        shape: const RoundedRectangleBorder(
          borderRadius: ExpressiveShapes.pill,
        ),
        padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 18),
        textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        shape: const RoundedRectangleBorder(
          borderRadius: ExpressiveShapes.pill,
        ),
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
      ),
    ),

    cardTheme: CardThemeData(
      shape: const RoundedRectangleBorder(borderRadius: ExpressiveShapes.large),
      color: scheme.surfaceContainer,
      elevation: 0,
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
    ),

    // FAB grande con esquinas desiguales: el elemento más expresivo de la app.
    floatingActionButtonTheme: FloatingActionButtonThemeData(
      shape: const RoundedRectangleBorder(
        borderRadius: ExpressiveShapes.asymmetric,
      ),
      backgroundColor: scheme.primaryContainer,
      foregroundColor: scheme.onPrimaryContainer,
      elevation: 0,
      focusElevation: 0,
      hoverElevation: 0,
      extendedPadding: const EdgeInsets.symmetric(horizontal: 28),
    ),

    appBarTheme: AppBarTheme(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: base.textTheme.headlineMedium?.copyWith(
        color: scheme.onSurface,
        fontWeight: FontWeight.w700,
      ),
    ),

    chipTheme: ChipThemeData(
      shape: const RoundedRectangleBorder(borderRadius: ExpressiveShapes.small),
      side: BorderSide.none,
      backgroundColor: scheme.surfaceContainerHigh,
    ),

    listTileTheme: const ListTileThemeData(
      shape: RoundedRectangleBorder(borderRadius: ExpressiveShapes.medium),
      contentPadding: EdgeInsets.symmetric(horizontal: 20, vertical: 8),
    ),

    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      shape: const RoundedRectangleBorder(
        borderRadius: ExpressiveShapes.medium,
      ),
      backgroundColor: scheme.inverseSurface,
      contentTextStyle: TextStyle(color: scheme.onInverseSurface),
    ),

    dialogTheme: const DialogThemeData(
      shape: RoundedRectangleBorder(borderRadius: ExpressiveShapes.extraLarge),
    ),

    progressIndicatorTheme: ProgressIndicatorThemeData(
      color: scheme.primary,
      linearTrackColor: scheme.surfaceContainerHighest,
      linearMinHeight: 8,
    ),
  );
}
