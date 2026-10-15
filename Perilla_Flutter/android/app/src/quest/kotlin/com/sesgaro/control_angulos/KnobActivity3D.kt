package com.sesgaro.control_angulos

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import com.meta.spatial.core.Color4
import com.meta.spatial.core.ComponentBase
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Sphere
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.ViewPanelRegistration
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import android.net.Uri

/// Perilla 3D del Quest: una perilla de puerta que flota frente al usuario,
/// sobre el passthrough, y se gira con la mano.
///
/// Se agarra pellizcando cerca de ella y se gira la muñeca, como una perilla
/// de verdad: lo que cuenta es cuánto rota la mano alrededor del eje de la
/// perilla, no dónde está. Solo manos (sin mandos).
///
/// Igual que la perilla 2D, no conecta nada: propone valores al panel de
/// opciones por [PanelBus] y muestra el estado que este le devuelve.
class KnobActivity3D : AppSystemActivity() {

    /// Igual que `AngleRange` en Dart. [sweep] es el recorrido de la perilla
    /// en grados; en los rangos con topes se centra arriba.
    private class Range(val min: Float, val max: Float, val sweep: Float, val wrap: Boolean) {
        val span get() = max - min

        fun clamp(v: Float) = if (wrap) ((v - min) % span + span) % span + min else v.coerceIn(min, max)

        /// Ángulo en la carátula: 0 arriba, positivo en sentido horario.
        fun dial(v: Float): Float {
            val t = (clamp(v) - min) / span
            return if (wrap) t * 360f else -sweep / 2 + t * sweep
        }
    }

    private val ranges = mapOf(
        "servo" to Range(0f, 180f, 270f, false),
        "full" to Range(0f, 360f, 360f, true),
        "signed" to Range(-90f, 90f, 180f, false),
    )

    // Estado que manda el panel de opciones (hilo principal) y lee el
    // sistema de la escena.
    @Volatile private var rangeName = "servo"
    @Volatile private var angle = 90f
    @Volatile private var measured: Float? = null
    @Volatile private var connected = false

    private val range get() = ranges[rangeName] ?: ranges.getValue("servo")

    private val busListener: (String, Map<*, *>) -> Unit = { method, args ->
        if (method == "state") {
            (args["range"] as? String)?.let { rangeName = it }
            (args["angle"] as? Number)?.let { if (!knob.grabbing) angle = it.toFloat() }
            measured = (args["measured"] as? Number)?.toFloat()
            connected = args["connected"] == true
        }
    }

    private val knob = KnobScene()
    private var label: TextView? = null

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PanelBus.started(this)
        PanelBus.listen(busListener)
    }

    override fun onDestroy() {
        PanelBus.unlisten(busListener)
        PanelBus.stopped(this)
        super.onDestroy()
    }

    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        scene.enablePassthrough(true)
        scene.setLightingEnvironment(
            Vector3(0.35f),
            Vector3(2.2f, 2.1f, 2.0f),
            -Vector3(0.3f, 1.0f, -0.8f),
            0.3f,
        )
        knob.build()
        systemManager.registerSystem(KnobSystem())
        // Pide el estado actual al panel de opciones.
        PanelBus.send(this, "hello")
    }

    override fun registerPanels(): List<PanelRegistration> = listOf(
        ViewPanelRegistration(
            R.id.knob3d_label,
            { _, ctx ->
                TextView(ctx).apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(170, 40, 10, 22))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    text = "—"
                }
            },
            { _ -> UIPanelSettings(QuadShapeOptions(0.16f, 0.06f)) },
            { view, _, _ -> label = view as TextView },
        ),
        // La escena inmersiva oculta las ventanas 2D: este botón regresa a ellas.
        ViewPanelRegistration(
            R.id.knob3d_back,
            { _, ctx ->
                Button(ctx).apply {
                    text = "Volver a 2D"
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                    background = GradientDrawable().apply {
                        cornerRadius = 200f
                        setColor(Color.rgb(188, 0, 75))
                    }
                    setOnClickListener { backTo2d() }
                }
            },
            { _ -> UIPanelSettings(QuadShapeOptions(0.14f, 0.045f)) },
            { _, _, _ -> },
        ),
    )

    /// Regresa a las ventanas 2D: avisa al panel de opciones (que abre la
    /// perilla 2D) y vuelve al inicio con el panel de opciones al frente,
    /// como indica Meta para pasar de inmersivo a panel.
    private fun backTo2d() {
        PanelBus.send(this, "knobMode", mapOf("mode" to "2d"))
        val panel = Intent(applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            panel,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("extra_launch_in_home_pending_intent", pending),
        )
        finishAndRemoveTask()
    }

    // --- Escena -------------------------------------------------------------

    /// Las piezas de la perilla. Todo cuelga de [root], cuyo eje +Z apunta
    /// hacia el usuario; el giro de la perilla es alrededor de ese eje.
    private inner class KnobScene {
        lateinit var root: Entity
        lateinit var rotor: Entity
        lateinit var ball: Entity
        lateinit var measuredDot: Entity
        private val ticks = mutableListOf<Pair<Entity, Float>>()
        private var ticksFor: String? = null

        var placed = false
        var rootPose = Pose(Vector3(0f, -10f, 0f), Quaternion())
        var grabbing = false
        private var ballLook = -1

        fun build() {
            root = Entity.create(listOf(Transform(rootPose)))
            // Roseta: esfera aplastada, como la placa de una perilla de puerta.
            child(root, Vector3(0f, 0f, 0f), sphere(0.09f, PLATE), Scale(Vector3(1f, 1f, 0.12f)))
            rotor = child(root, Vector3(0f, 0f, 0f))
            // Cuello y bola de la perilla, y el punto blanco que indica hacia dónde apunta.
            child(rotor, Vector3(0f, 0f, 0.028f), sphere(0.02f, KNOB), Scale(Vector3(1f, 1f, 1.5f)))
            ball = child(rotor, Vector3(0f, 0f, 0.066f), sphere(0.042f, KNOB))
            val dotY = 0.028f
            val dotZ = 0.066f + sqrt(0.042f * 0.042f - dotY * dotY)
            child(rotor, Vector3(0f, dotY, dotZ), sphere(0.0075f, WHITE))
            // Posición real del motor sobre el borde de la carátula.
            measuredDot = child(root, Vector3(0f, 0f, 0.012f), sphere(0.0075f, MEASURED), Visible(false))
            Entity.createPanelEntity(
                R.id.knob3d_label,
                Transform(Pose(Vector3(0f, 0.13f, 0f), Quaternion())),
                TransformParent(root),
            )
            Entity.createPanelEntity(
                R.id.knob3d_back,
                Transform(Pose(Vector3(0f, -0.13f, 0f), Quaternion())),
                TransformParent(root),
            )
        }

        /// Marcas de la carátula; se rehacen al cambiar de rango.
        private fun buildTicks(r: Range) {
            ticks.forEach { it.first.destroy() }
            ticks.clear()
            val n = 24
            for (i in 0..n) {
                if (r.wrap && i == n) break
                val a = if (r.wrap) 360f * i / n else -r.sweep / 2 + r.sweep * i / n
                val major = i % 6 == 0
                val inner = if (major) 0.062f else 0.068f
                val e = Entity.create(
                    listOf(
                        Mesh(Uri.parse("mesh://box")),
                        Box(Vector3(-0.0016f, inner, 0f), Vector3(0.0016f, 0.078f, 0.004f)),
                        material(TICK),
                        Transform(Pose(Vector3(0f, 0f, 0.0105f), rollZ(-a))),
                        TransformParent(root),
                    ),
                )
                ticks += e to a
            }
            ticksFor = rangeName
        }

        fun update(value: Float, hover: Boolean) {
            val r = range
            if (ticksFor != rangeName) buildTicks(r)
            val a = r.dial(value)
            rotor.setComponent(Transform(Pose(Vector3(0f, 0f, 0f), rollZ(-a))))

            // Marcas activas desde el origen (0 si está en el rango) hasta el valor.
            val origin = r.dial(if (r.wrap) r.min else 0f.coerceIn(r.min, r.max))
            val lo = minOf(origin, a) - 0.5f
            val hi = maxOf(origin, a) + 0.5f
            for ((e, ta) in ticks) {
                val on = ta in lo..hi
                e.setComponent(material(if (on) KNOB else TICK))
            }

            val m = measured
            measuredDot.setComponent(Visible(m != null))
            if (m != null) {
                val ma = r.dial(m) * DEG
                val mp = Vector3(sin(ma) * 0.084f, cos(ma) * 0.084f, 0.012f)
                measuredDot.setComponent(Transform(Pose(mp, Quaternion())))
            }

            val look = if (grabbing) 2 else if (hover) 1 else 0
            if (look != ballLook) {
                ballLook = look
                ball.setComponent(material(if (look == 2) KNOB_GRAB else if (look == 1) KNOB_HOVER else KNOB))
            }
        }

        fun place(pose: Pose) {
            rootPose = pose
            root.setComponent(Transform(pose))
            placed = true
        }

        private fun child(parent: Entity, at: Vector3, vararg parts: ComponentBase): Entity =
            Entity.create(listOf(Transform(Pose(at, Quaternion())), TransformParent(parent)) + parts)

        private fun sphere(radius: Float, color: Color4): List<ComponentBase> =
            listOf(Mesh(Uri.parse("mesh://sphere")), Sphere(radius), material(color))

        private fun child(parent: Entity, at: Vector3, parts: List<ComponentBase>, vararg more: ComponentBase): Entity =
            child(parent, at, *(parts + more).toTypedArray())
    }

    // --- Interacción ----------------------------------------------------------

    /// Cada cuadro: coloca la perilla frente al usuario la primera vez, sigue
    /// el pellizco y el giro de la muñeca, y actualiza la escena.
    private inner class KnobSystem : SystemBase() {
        private var hand: Entity? = null
        private var handAxis = 0           // eje local de la mano que se mide
        private var lastHandAngle = 0f     // ángulo de la mano en el plano de la perilla
        private var twist = 0f             // giro acumulado desde que se agarró (°)
        private var smooth = 0f            // giro suavizado
        private var applied = 0f           // giro ya aplicado al valor (con histéresis)
        private var startDial = 0f
        private var lastSentAt = 0L
        private var lastSent = Float.NaN
        private var label = ""
        override fun execute() {
            val body = Query.where { has(AvatarBody.id) }.eval()
                .filter { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
                .firstOrNull()?.getComponent<AvatarBody>() ?: return

            // Los primeros cuadros el visor aún no está rastreado y reporta la
            // cabeza en el origen (a la altura del piso): se espera a una pose
            // real antes de colocar la perilla, si no queda bajo el piso.
            val head = scene.getViewerPose()
            if (head.t.y < MIN_HEAD_HEIGHT) return
            if (!knob.placed || head.t.distanceTo(knob.rootPose.t) > 1.6f) placeInFront(head)

            val center = knob.rootPose * Vector3(0f, 0f, 0.066f)
            val hands = listOf(body.rightHand to PINCH_R, body.leftHand to PINCH_L)
            var hover = false

            if (hand == null) {
                for ((h, bits) in hands) {
                    val c = h.tryGetComponent<Controller>() ?: continue
                    if (!c.isActive || c.type != ControllerType.HAND) continue
                    val pose = h.tryGetComponent<Transform>()?.transform ?: continue
                    val near = pose.t.distanceTo(center) < GRAB_RADIUS
                    hover = hover || near
                    val pressedNow = c.buttonState and c.changedButtons and bits
                    if (near && pressedNow != 0) {
                        startGrab(h)
                        break
                    }
                }
            } else {
                val h = hand!!
                val c = h.tryGetComponent<Controller>()
                val pose = h.tryGetComponent<Transform>()?.transform
                val bits = if (h == body.rightHand) PINCH_R else PINCH_L
                if (c == null || pose == null || !c.isActive || (c.buttonState and bits) == 0) {
                    endGrab()
                } else {
                    followTwist(pose)
                }
            }

            knob.update(angle, hover)
            updateLabel()
        }

        private fun placeInFront(head: Pose) {
            // Medio metro al frente y un poco abajo de los ojos, mirando al usuario.
            val f = head.forward()
            var flat = Vector3(f.x, 0f, f.z)
            if (flat.length() < 1e-3f) flat = Vector3(0f, 0f, -1f)
            flat = flat.normalize()
            val pos = head.t + flat * 0.45f + Vector3(0f, -0.2f, 0f)
            // Girar en Y para que el +Z local apunte hacia la cabeza.
            val toHead = -flat
            val yaw = atan2(toHead.x, toHead.z)
            knob.place(Pose(pos, Quaternion(cos(yaw / 2), 0f, sin(yaw / 2), 0f)))
        }

        private fun startGrab(h: Entity) {
            val q = h.tryGetComponent<Transform>()?.transform?.q ?: return
            hand = h
            knob.grabbing = true
            // El eje de la mano más perpendicular al de la perilla es el que
            // mejor refleja el giro de la muñeca, sea cual sea la postura.
            val axis = knobAxis()
            val candidates = listOf(q * Vector3(1f, 0f, 0f), q * Vector3(0f, 1f, 0f), q * Vector3(0f, 0f, 1f))
            handAxis = candidates.indices.minBy { abs(candidates[it].dot(axis)) }
            lastHandAngle = handAngle(q)
            twist = 0f
            smooth = 0f
            applied = 0f
            startDial = range.dial(angle)
        }

        private fun followTwist(pose: Pose) {
            val a = handAngle(pose.q)
            var d = a - lastHandAngle
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            lastHandAngle = a
            // Visto desde el usuario el ángulo crece en sentido antihorario;
            // girar a la derecha (horario) sube el valor, como la perilla 2D.
            twist -= d
            smooth += (twist - smooth) * SMOOTHING
            // Histéresis: el seguimiento de manos tiembla un poco.
            if (abs(smooth - applied) < DEADBAND) return
            applied = smooth

            val r = range
            val dial = if (r.wrap) startDial + applied else (startDial + applied).coerceIn(-r.sweep / 2, r.sweep / 2)
            var value = r.min + (if (r.wrap) dial else dial + r.sweep / 2) / r.sweep * r.span
            value = r.clamp(round(value))
            if (value == angle) return
            angle = value
            val now = System.currentTimeMillis()
            if (now - lastSentAt >= SEND_EVERY_MS) {
                lastSentAt = now
                lastSent = value
                PanelBus.send(this@KnobActivity3D, "set", mapOf("value" to value.toDouble(), "final" to false))
            }
        }

        private fun endGrab() {
            hand = null
            knob.grabbing = false
            PanelBus.send(this@KnobActivity3D, "set", mapOf("value" to angle.toDouble(), "final" to true))
        }

        private fun knobAxis() = knob.rootPose.q * Vector3(0f, 0f, 1f)

        /// Ángulo (°) del eje elegido de la mano proyectado en el plano de la
        /// carátula, medido en los ejes X/Y de la perilla.
        private fun handAngle(q: Quaternion): Float {
            val v = q * when (handAxis) {
                0 -> Vector3(1f, 0f, 0f)
                1 -> Vector3(0f, 1f, 0f)
                else -> Vector3(0f, 0f, 1f)
            }
            val x = v.dot(knob.rootPose.q * Vector3(1f, 0f, 0f))
            val y = v.dot(knob.rootPose.q * Vector3(0f, 1f, 0f))
            return atan2(y, x) / DEG
        }

        private fun updateLabel() {
            val m = measured
            val text = format(angle) + "°" +
                if (!connected) "   sin conexión" else if (m == null) "" else "   real ${format(m)}°"
            if (text == label) return
            label = text
            this@KnobActivity3D.label?.let { tv -> tv.post { tv.text = text } }
        }
    }

    private fun format(v: Float): String {
        val r = round(v * 10f) / 10f
        return if (r == floor(r)) r.toInt().toString() else "%.1f".format(r)
    }

    private fun material(c: Color4) = Material().apply {
        baseColor = c
        roughness = 0.45f
    }

    private companion object {
        const val DEG = (PI / 180).toFloat()

        // Pellizco de índice: ButtonA en la mano derecha, ButtonX en la
        // izquierda. Con la mano cerrada (agarre) llega como squeeze.
        val PINCH_R = ButtonBits.ButtonA or ButtonBits.ButtonSqueezeR
        val PINCH_L = ButtonBits.ButtonX or ButtonBits.ButtonSqueezeL

        const val GRAB_RADIUS = 0.14f      // m desde el centro de la bola
        const val MIN_HEAD_HEIGHT = 0.3f   // m; por debajo, el visor aún no está rastreado
        const val SMOOTHING = 0.35f        // filtro por cuadro (0..1)
        const val DEADBAND = 1.0f          // ° de giro antes de mover el valor
        const val SEND_EVERY_MS = 40L

        val KNOB = Color4(0.74f, 0.0f, 0.29f, 1f)          // rosa del tema
        val KNOB_HOVER = Color4(0.86f, 0.24f, 0.48f, 1f)
        val KNOB_GRAB = Color4(1.0f, 0.42f, 0.64f, 1f)
        val PLATE = Color4(0.18f, 0.12f, 0.15f, 1f)
        val TICK = Color4(0.82f, 0.74f, 0.77f, 1f)
        val WHITE = Color4(1f, 1f, 1f, 1f)
        val MEASURED = Color4(1.0f, 0.62f, 0.18f, 1f)      // naranja: posición real

        /// Rotación de [deg] grados alrededor del eje Z local.
        fun rollZ(deg: Float): Quaternion {
            val h = deg * DEG / 2
            return Quaternion(cos(h), 0f, 0f, sin(h))
        }
    }
}
