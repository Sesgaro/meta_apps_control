enum AngleRange {
  servo(0, 180, 270, '0–180°'),
  full(0, 360, 360, '0–360°'),
  // Recorrido igual al rango: la perilla gira 1:1 con el ángulo enviado.
  signed(-90, 90, 180, '±90°');

  const AngleRange(this.min, this.max, this.sweep, this.label);
  final double min;
  final double max;

  /// Grados que recorre la perilla en pantalla de min a max.
  final double sweep;
  final String label;

  bool get wrap => this == AngleRange.full;

  double clamp(double v) => wrap ? v % 360 : v.clamp(min, max);
}
