package com.sesgaro.control_angulos

/// Panel de la perilla en el Quest (solo se declara en el manifiesto del
/// flavor quest). Arranca en `knobMain` en vez de `main`.
class KnobActivity : PanelActivity() {
    override fun getDartEntrypointFunctionName(): String = "knobMain"

    // La perilla no usa BLE ni la paleta del sistema; sin plugins, su motor
    // no interfiere con flutter_blue_plus del panel de opciones.
    override val registerPlugins = false
}
