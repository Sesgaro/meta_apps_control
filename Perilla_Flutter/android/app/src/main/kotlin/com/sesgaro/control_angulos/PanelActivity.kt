package com.sesgaro.control_angulos

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterActivityLaunchConfigs.BackgroundMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/// Base de las ventanas de la app. En el Quest cada actividad es un panel
/// independiente con su propio motor de Flutter (y su propio isolate), así
/// que el estado se sincroniza con mensajes a través de [PanelBus].
abstract class PanelActivity : FlutterActivity() {
    private var channel: MethodChannel? = null

    /// False en paneles que no necesitan plugins (BLE, paleta del sistema).
    protected open val registerPlugins = true

    // En el Quest la ventana no tiene fondo: solo se ven las tarjetas,
    // flotando sobre el passthrough.
    override fun getBackgroundMode(): BackgroundMode =
        if (packageName.endsWith(".quest")) BackgroundMode.transparent
        else BackgroundMode.opaque

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PanelBus.started(this)
    }

    override fun onDestroy() {
        PanelBus.stopped(this)
        super.onDestroy()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        if (registerPlugins) super.configureFlutterEngine(flutterEngine)
        channel = PanelBus.attach(flutterEngine, this)
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        channel?.let(PanelBus::detach)
        channel = null
        if (registerPlugins) super.cleanUpFlutterEngine(flutterEngine)
    }
}

/// Reenvía cada mensaje de un panel a los demás, sean de Flutter (un
/// MethodChannel por motor) o nativos (la perilla 3D, en Kotlin).
///
/// Mensajes que atiende aquí en vez de reenviarlos:
///   openKnob | openKnob3d | openCanvas   abre (o trae al frente) ese panel
///   closeKnob | closeKnob3d              cierra ese panel si está abierto
object PanelBus {
    private const val NAME = "control_angulos/panels"

    // Por nombre: KnobActivity3D solo existe en el flavor quest.
    private const val KNOB_3D = "com.sesgaro.control_angulos.KnobActivity3D"
    private val panels = mapOf(
        "openKnob" to KnobActivity::class.java.name,
        "openKnob3d" to KNOB_3D,
        "openCanvas" to CanvasActivity::class.java.name,
    )
    private val closers = mapOf(
        "closeKnob" to KnobActivity::class.java.name,
        "closeKnob3d" to KNOB_3D,
    )

    private val channels = mutableListOf<MethodChannel>()
    private val listeners = mutableListOf<(String, Map<*, *>) -> Unit>()
    private val running = mutableMapOf<String, WeakReference<Activity>>()
    private val main = Handler(Looper.getMainLooper())

    fun attach(engine: FlutterEngine, activity: Activity): MethodChannel {
        val ch = MethodChannel(engine.dartExecutor.binaryMessenger, NAME)
        ch.setMethodCallHandler { call, result ->
            dispatch(activity, call.method, call.arguments as? Map<*, *> ?: emptyMap<Any, Any>(), from = ch)
            result.success(null)
        }
        channels += ch
        return ch
    }

    fun detach(ch: MethodChannel) {
        ch.setMethodCallHandler(null)
        channels -= ch
    }

    /// Un panel nativo escucha los mensajes de los demás.
    fun listen(listener: (String, Map<*, *>) -> Unit) {
        listeners += listener
    }

    fun unlisten(listener: (String, Map<*, *>) -> Unit) {
        listeners -= listener
    }

    /// Un panel nativo manda un mensaje. Se puede llamar desde cualquier
    /// hilo: los canales de Flutter solo aceptan llamadas en el principal.
    fun send(activity: Activity, method: String, args: Map<String, Any?> = emptyMap()) {
        main.post { dispatch(activity, method, args, from = null) }
    }

    fun started(activity: Activity) {
        running[activity.javaClass.name] = WeakReference(activity)
    }

    fun stopped(activity: Activity) {
        if (running[activity.javaClass.name]?.get() === activity) running.remove(activity.javaClass.name)
    }

    private fun dispatch(activity: Activity, method: String, args: Map<*, *>, from: MethodChannel?) {
        panels[method]?.let { name ->
            // Tarea nueva con otra afinidad = otro panel en Horizon OS.
            activity.startActivity(
                Intent().setClassName(activity, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        closers[method]?.let { name ->
            running[name]?.get()?.finishAndRemoveTask()
            return
        }
        for (other in channels.toList()) {
            if (other !== from) other.invokeMethod(method, args)
        }
        if (from != null) for (l in listeners.toList()) l(method, args)
    }
}
