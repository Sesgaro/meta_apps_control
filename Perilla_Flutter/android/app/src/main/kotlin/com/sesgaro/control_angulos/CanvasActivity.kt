package com.sesgaro.control_angulos

/// Lienzo de dibujo del Quest, que se abre con el gesto secreto (solo se
/// declara en el manifiesto del flavor quest). Arranca en `canvasMain`.
class CanvasActivity : PanelActivity() {
    override fun getDartEntrypointFunctionName(): String = "canvasMain"

    override val registerPlugins = false
}
