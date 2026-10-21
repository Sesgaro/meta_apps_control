package com.sesgaro.control_angulos

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.meta.spatial.core.Color4
import com.meta.spatial.core.ComponentBase
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Query
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.IsdkFeature
import com.meta.spatial.isdk.IsdkGrabFilter
import com.meta.spatial.isdk.IsdkGrabMovementType
import com.meta.spatial.isdk.IsdkGrabState
import com.meta.spatial.isdk.IsdkGrabbable
import com.meta.spatial.isdk.IsdkSphereCollider
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/// Tablero 3D del gato en el Quest: una mesa virtual frente al usuario, sobre
/// el passthrough, con la cuadrícula a escala de la máquina, las fichas, la
/// casilla que ve la matriz IR y la cabeza en su posición real.
///
/// Dos modos (los elige el panel 2D, `mode`):
///  - Resortera: jalas la pelota y la cabeza va a donde caería. Al soltar, la
///    pelota vuela y se pide "tirar"; si la matriz IR ve la cabeza en una
///    casilla libre la pelota se cuela por un hueco, si no rebota y se va.
///  - Grúa: un joystick mueve la cabeza y un botón suelta la garra (tirar).
///
/// Dos visores: cada uno calibra el tablero sobre los mismos dos puntos reales
/// (botón Calibrar: se agarran dos marcadores y se ponen en las esquinas de
/// tu lado del tablero, izquierda y derecha). Así los dos miden en mm de la
/// máquina y cada uno manda su cabeza y manos (PoseFrame) por la ESP; aquí se
/// dibuja al otro jugador.
///
/// Solo manos (agarre del Interaction SDK). No conecta nada: propone
/// movimientos y tiros al panel 2D (dueño del BLE) por [PanelBus] y dibuja el
/// estado que este le reenvía.
class GameActivity3D : AppSystemActivity() {

    /// Último estado del juego que llegó del panel 2D (hilo principal); lo
    /// lee el sistema de la escena.
    @Volatile private var game: Map<*, *> = emptyMap<Any, Any>()

    /// Última pose del otro visor y cuándo llegó (System.nanoTime).
    @Volatile private var remotePose: ByteArray? = null
    @Volatile private var remoteAt = 0L

    private val busListener: (String, Map<*, *>) -> Unit = { method, args ->
        when (method) {
            "game" -> game = args
            "poses" -> (args["data"] as? ByteArray)?.let {
                remotePose = it
                remoteAt = System.nanoTime()
            }
        }
    }

    private val scene3d = BoardScene()
    private var sfx: Sfx? = null
    private var status: TextView? = null
    private var calibButton: Button? = null

    /// Lo pide el botón Calibrar; lo atiende el sistema de la escena.
    @Volatile private var calibToggle = false

    override fun registerFeatures(): List<SpatialFeature> = listOf(
        VRFeature(this),
        // Manos del Interaction SDK: agarre de la pelota, el joystick y el botón.
        IsdkFeature(this, spatial, systemManager),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PanelBus.started(this)
        PanelBus.listen(busListener)
    }

    override fun onDestroy() {
        sfx?.release()
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
        sfx = Sfx()
        systemManager.registerSystem(GameSystem())
        PanelBus.send(this, "hello")
    }

    override fun registerPanels(): List<PanelRegistration> = listOf(
        ViewPanelRegistration(
            R.id.game3d_status,
            { _, ctx ->
                TextView(ctx).apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(190, 40, 10, 22))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(24, 8, 24, 8)
                    text = "Esperando datos…"
                }
            },
            { _ -> UIPanelSettings(QuadShapeOptions(0.36f, 0.12f)) },
            { view, _, _ -> status = view as TextView },
        ),
        button(R.id.game3d_new, "Nuevo juego") { PanelBus.send(this, "newGame") },
        // La escena inmersiva oculta las ventanas 2D: este botón regresa a ellas.
        button(R.id.game3d_back, "Volver a 2D") { backTo2d() },
        button(R.id.game3d_calib, "Calibrar", { calibButton = it }) { calibToggle = true },
    )

    private fun button(id: Int, label: String, onView: (Button) -> Unit = {}, onClick: () -> Unit) = ViewPanelRegistration(
        id,
        { _, ctx ->
            Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                background = GradientDrawable().apply {
                    cornerRadius = 200f
                    setColor(Color.rgb(188, 0, 75))
                }
                setOnClickListener { onClick() }
            }
        },
        { _ -> UIPanelSettings(QuadShapeOptions(0.14f, 0.045f)) },
        { view: View, _, _ -> onView(view as Button) },
    )

    /// Regresa a las ventanas 2D: vuelve al inicio con el panel 2D al frente,
    /// como indica Meta para pasar de inmersivo a panel.
    private fun backTo2d() {
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

    // --- Lectura del estado ----------------------------------------------------

    private fun num(key: String, default: Float = 0f) = (game[key] as? Number)?.toFloat() ?: default
    private fun int(key: String, default: Int = 0) = (game[key] as? Number)?.toInt() ?: default

    /// Medidas del tablero en mm, las que tiene configuradas la ESP.
    private class Geometry(
        val xMin: Float, val xMax: Float, val yMin: Float, val yMax: Float,
        val x0: Float, val xStep: Float, val y0: Float, val yStep: Float,
    ) {
        val left get() = x0 - xStep / 2
        val right get() = x0 + 2.5f * xStep
        val bottom get() = y0 - yStep / 2
        val top get() = y0 + 2.5f * yStep
        fun cellX(c: Int) = x0 + (c % 3) * xStep
        fun cellY(c: Int) = y0 + (c / 3) * yStep
    }

    private fun geometry(): Geometry? {
        if (!game.containsKey("xStep")) return null
        val g = Geometry(
            num("xMin"), num("xMax"), num("yMin"), num("yMax"),
            num("x0"), num("xStep"), num("y0"), num("yStep"),
        )
        return if (g.xStep > 0 && g.yStep > 0) g else null
    }

    /// Solo el jugador en turno mueve la cabeza (fase 1 = turno).
    private fun canMove() =
        game["connected"] == true && int("phase") == 1 && int("turn") == int("me")

    private fun clawMode() = int("mode") == 1

    // --- Escena -------------------------------------------------------------------

    /// La mesa virtual. Todo cuelga de [root]: su +Z apunta al usuario, +X a su
    /// derecha. El eje X de la máquina va a la derecha y el Y se aleja del
    /// usuario (-Z), como si viera la máquina desde el frente. La resortera,
    /// el joystick y el botón quedan en el borde de tu lado.
    ///
    /// Lo que se agarra (pelota, perilla del joystick, botón) va suelto en la
    /// escena, sin padre, para que el agarre lo mueva en coordenadas del mundo.
    private inner class BoardScene {
        lateinit var root: Entity
        lateinit var head: Entity
        lateinit var target: Entity
        private lateinit var timeBar: Entity
        private lateinit var landing: Entity
        private val winDots = mutableListOf<Entity>()
        private val confetti = mutableListOf<Entity>()
        private var confettiPos = Array(CONFETTI) { Vector3(0f) }
        private var confettiVel = Array(CONFETTI) { Vector3(0f) }
        private var confettiLeft = 0f
        private var winLook = ""
        private var barLook = -1

        /// Bamboleo de la garra (local, se suma a la cabeza).
        var headSway = Vector3(0f)
        lateinit var base: Entity
        private val tiles = mutableListOf<Entity>()
        private val holes = mutableListOf<Entity>()
        private val pieces = mutableListOf<Pair<Entity, Entity>>()
        lateinit var geo: Geometry

        var rootPose = Pose(Vector3(0f, -10f, 0f), Quaternion())
        var placed = false
        var built = false

        /// Escala de la mesa: la calibración la ajusta a la distancia real
        /// entre los dos puntos (1 con el tablero de verdad).
        var scale = 1f
        var calibrated = false
        var calibrating = false
        lateinit var markA: Entity
        lateinit var markB: Entity
        var boardW = 0f

        // El otro jugador.
        private val avatar = mutableListOf<Entity>()
        private var avatarColor = -1

        /// Metros por mm: el tablero mide [BOARD_M] de ancho.
        var k = 0.001f
        var cx = 0f
        var cy = 0f
        var halfH = 0f

        // Resortera.
        lateinit var ball: Entity
        lateinit var slingRest: Vector3
        private lateinit var forkL: Vector3
        private lateinit var forkR: Vector3
        private val bands = mutableListOf<Entity>()
        private val dots = mutableListOf<Entity>()
        private val slingParts = mutableListOf<Entity>()

        // Grúa.
        lateinit var knob: Entity
        lateinit var knobRest: Vector3
        lateinit var button: Entity
        lateinit var buttonAt: Vector3
        private val stick = mutableListOf<Entity>()
        private val clawParts = mutableListOf<Entity>()
        private val headParts = mutableListOf<Entity>()

        /// Altura de la cabeza sobre la mesa (la garra sube y baja).
        var headLift = HEAD_LIFT

        private var baseLook = -1
        private var modeLook = -1
        private val tileLook = IntArray(9) { -1 }
        private val pieceLook = IntArray(9) { -1 }

        fun local(xMm: Float, yMm: Float, lift: Float = 0f) =
            Vector3((xMm - cx) * k, lift, -(yMm - cy) * k)

        /// Punto local de la mesa -> mm de la máquina.
        fun toMm(l: Vector3): Pair<Float, Float> = (cx + l.x / k) to (cy - l.z / k)

        fun toLocal(world: Vector3) = (rootPose.inverse() * world) * (1f / scale)
        fun toWorld(l: Vector3) = rootPose * (l * scale)

        /// Punto local -> mm del tablero (x, y de la máquina; h = altura).
        fun toBoardMm(l: Vector3) = Triple(cx + l.x / k, cy - l.z / k, l.y / k)
        fun fromBoardMm(x: Float, y: Float, h: Float) = Vector3((x - cx) * k, h * k, -(y - cy) * k)

        /// Se arma una vez, cuando llega la geometría de la ESP.
        fun build(g: Geometry) {
            geo = g
            k = BOARD_M / (g.right - g.left)
            cx = (g.left + g.right) / 2
            cy = (g.bottom + g.top) / 2
            val w = (g.right - g.left) * k
            val h = (g.top - g.bottom) * k
            halfH = h / 2
            boardW = w
            val cell = minOf(g.xStep, g.yStep) * k

            root = Entity.create(listOf(Transform(rootPose)))
            base = box(root, Vector3(-w / 2 - 0.02f, -0.015f, -h / 2 - 0.02f), Vector3(w / 2 + 0.02f, 0f, h / 2 + 0.02f), BASE_LOCKED)
            // Cuadrícula.
            for (i in 1..2) {
                val x = (g.left + i * g.xStep - cx) * k
                val z = -(g.bottom + i * g.yStep - cy) * k
                box(root, Vector3(x - 0.002f, 0f, -h / 2), Vector3(x + 0.002f, 0.004f, h / 2), GRID)
                box(root, Vector3(-w / 2, 0f, z - 0.002f), Vector3(w / 2, 0.004f, z + 0.002f), GRID)
            }
            for (c in 0 until 9) {
                val center = local(g.cellX(c), g.cellY(c))
                // Baldosa: se ilumina con el sensor IR o el último tiro.
                val half = cell * 0.44f
                tiles += box(root, center + Vector3(-half, 0.0005f, -half), center + Vector3(half, 0.002f, half), IR, Visible(false))
                // Hueco por donde se cuela la pelota.
                val hole = cell * 0.2f
                holes += box(root, center + Vector3(-hole, 0.0005f, -hole), center + Vector3(hole, 0.003f, hole), HOLE, Visible(false))
                // Sensor IR al centro de la casilla.
                sphere(root, center + Vector3(0f, 0.002f, 0f), cell * 0.04f, GRID)
                // Fichas: los modelos PiezaX/PiezaO (1 m de ancho, ver tool/stl_a_glb.py).
                pieces += piece("pieza_x", center, cell * 0.7f) to piece("pieza_o", center, cell * 0.6f)
            }
            // Cabeza en su posición real; la referencia (blanco) sobre la mesa.
            head = Entity.create(listOf(Transform(Pose(local(cx, cy, HEAD_LIFT), Quaternion())), TransformParent(root)))
            headParts += sphere(head, Vector3(0f, 0f, 0f), HEAD_R, HEAD)
            headParts += box(head, Vector3(-0.003f, 0f, -0.003f), Vector3(0.003f, 0.08f, 0.003f), HEAD)
            // Garra: tres dedos alrededor de la cabeza (solo en modo grúa).
            for (i in 0 until 3) {
                val a = i * 2 * Math.PI.toFloat() / 3
                val at = Vector3(cos(a) * 0.022f, -0.012f, sin(a) * 0.022f)
                clawParts += box(head, at + Vector3(-0.003f, -0.02f, -0.003f), at + Vector3(0.003f, 0.012f, 0.003f), CLAW, Visible(false))
            }
            target = sphere(root, local(cx, cy, 0.004f), 0.008f, WHITE)
            // Dónde caerá la pelota mientras apuntas: un disco amarillo.
            landing = Entity.create(
                listOf(
                    Mesh(Uri.parse("mesh://sphere")),
                    Sphere(0.022f),
                    material(MARK_B),
                    Transform(Pose(Vector3(0f), Quaternion())),
                    Scale(Vector3(1f, 0.12f, 1f)),
                    TransformParent(root),
                    Visible(false),
                ),
            )
            // Tiempo del turno: barra al fondo de la mesa que se acorta.
            timeBar = Entity.create(
                listOf(
                    Mesh(Uri.parse("mesh://box")),
                    Box(Vector3(0f, 0f, -0.006f), Vector3(w, 0.008f, 0.006f)),
                    material(PLACED),
                    Transform(Pose(Vector3(-w / 2, 0f, -h / 2 - 0.035f), Quaternion())),
                    Scale(Vector3(0.001f, 1f, 1f)),
                    TransformParent(root),
                ),
            )
            // Tres en línea: puntos dorados de la primera a la última casilla.
            repeat(WIN_DOTS) { winDots += sphere(root, Vector3(0f), cell * 0.07f, GOLD, Visible(false)) }
            repeat(CONFETTI) { i ->
                confetti += sphere(root, Vector3(0f), 0.006f, CONFETTI_COLORS[i % CONFETTI_COLORS.size], Visible(false))
            }

            buildSlingshot(h)
            buildClawControls(h)

            // Calibración: esquina izquierda (A, cian) y derecha (B, amarilla)
            // del borde de tu lado.
            markA = grabbable(toWorld(Vector3(-w / 2, 0f, h / 2)), MARK_R, MARK_A, IsdkGrabMovementType.Direct)
            markB = grabbable(toWorld(Vector3(w / 2, 0f, h / 2)), MARK_R, MARK_B, IsdkGrabMovementType.Direct)
            markA.setComponent(Visible(false))
            markB.setComponent(Visible(false))

            // Otro jugador: cabeza, visor y manos.
            avatar += worldSphere(0.08f, WHITE)
            avatar += worldSphere(0.035f, HOLE)
            avatar += worldSphere(0.03f, WHITE)
            avatar += worldSphere(0.03f, WHITE)
            avatar += worldSphere(BALL_R, BALL)

            Entity.createPanelEntity(
                R.id.game3d_status,
                Transform(Pose(Vector3(0f, 0.2f, -h / 2 - 0.04f), Quaternion())),
                TransformParent(root),
            )
            Entity.createPanelEntity(
                R.id.game3d_new,
                Transform(Pose(Vector3(-0.08f, 0.12f, -h / 2 - 0.04f), Quaternion())),
                TransformParent(root),
            )
            Entity.createPanelEntity(
                R.id.game3d_back,
                Transform(Pose(Vector3(0.08f, 0.12f, -h / 2 - 0.04f), Quaternion())),
                TransformParent(root),
            )
            Entity.createPanelEntity(
                R.id.game3d_calib,
                Transform(Pose(Vector3(0f, 0.07f, -h / 2 - 0.04f), Quaternion())),
                TransformParent(root),
            )
            built = true
            // Calibración de esta sesión (sobrevive a salir y volver al 3D).
            savedCalibration?.let { (pose, sc) ->
                scale = sc
                calibrated = true
                place(pose)
            }
        }

        /// Resortera al centro del borde de tu lado: poste, horquilla y pelota.
        private fun buildSlingshot(h: Float) {
            val z = h / 2 + SLING_DIST
            slingRest = Vector3(0f, SLING_H, z)
            forkL = Vector3(-0.045f, SLING_H + 0.02f, z)
            forkR = Vector3(0.045f, SLING_H + 0.02f, z)
            slingParts += box(root, Vector3(-0.006f, 0f, z - 0.006f), Vector3(0.006f, SLING_H - 0.03f, z + 0.006f), WOOD)
            slingParts += box(root, Vector3(-0.05f, SLING_H - 0.036f, z - 0.006f), Vector3(0.05f, SLING_H - 0.024f, z + 0.006f), WOOD)
            for (f in listOf(forkL, forkR)) {
                slingParts += box(root, Vector3(f.x - 0.006f, SLING_H - 0.03f, z - 0.006f), Vector3(f.x + 0.006f, f.y, z + 0.006f), WOOD)
            }
            repeat(2 * BAND_DOTS) { bands += sphere(root, slingRest, 0.004f, BAND) }
            repeat(TRAJ_DOTS) { dots += sphere(root, slingRest, 0.005f, WHITE, Visible(false)) }
            ball = grabbable(toWorld(slingRest), BALL_R, BALL, IsdkGrabMovementType.Direct)
            slingParts += bands
            slingParts += ball
        }

        /// Joystick a la izquierda y botón a la derecha, como una máquina de peluches.
        private fun buildClawControls(h: Float) {
            val z = h / 2 + 0.09f
            val joyBase = Vector3(-0.13f, 0f, z)
            knobRest = joyBase + Vector3(0f, JOY_H, 0f)
            clawParts += box(root, joyBase + Vector3(-0.04f, 0f, -0.04f), joyBase + Vector3(0.04f, 0.015f, 0.04f), CONSOLE)
            repeat(STICK_DOTS) { stick += sphere(root, knobRest, 0.006f, GRID) }
            knob = grabbable(toWorld(knobRest), 0.018f, BUTTON, IsdkGrabMovementType.Direct)
            buttonAt = Vector3(0.13f, 0.025f, z)
            clawParts += box(root, Vector3(0.09f, 0f, z - 0.04f), Vector3(0.17f, 0.015f, z + 0.04f), CONSOLE)
            // El botón no se mueve al agarrarlo: agarrarlo (o tocarlo) es apretarlo.
            button = grabbable(toWorld(buttonAt), 0.025f, BUTTON, IsdkGrabMovementType.None)
            clawParts += stick
            clawParts += knob
            clawParts += button
        }

        fun place(pose: Pose) {
            rootPose = pose
            if (built) {
                root.setComponent(Transform(pose))
                root.setComponent(Scale(Vector3(scale)))
            }
            placed = true
        }

        /// Empieza o termina la calibración. Al terminar, la mesa se acomoda
        /// para que sus esquinas de tu lado queden en los marcadores.
        fun toggleCalibration() {
            if (!calibrating) {
                setWorld(markA, Vector3(-boardW / 2, 0f, halfH))
                setWorld(markB, Vector3(boardW / 2, 0f, halfH))
                listOf(markA, markB).forEach {
                    it.setComponent(Visible(true))
                    setGrabbable(it, true)
                }
                calibrating = true
                return
            }
            val a = markA.getComponent<Transform>().transform.t - Vector3(0f, MARK_R, 0f)
            val b = markB.getComponent<Transform>().transform.t - Vector3(0f, MARK_R, 0f)
            listOf(markA, markB).forEach {
                it.setComponent(Visible(false))
                setGrabbable(it, false)
            }
            calibrating = false
            val d = Vector3(b.x - a.x, 0f, b.z - a.z)
            if (d.length() < 0.05f) return // marcadores encimados: se ignora
            val yaw = atan2(-d.z, d.x)
            val q = Quaternion(cos(yaw / 2), 0f, sin(yaw / 2), 0f)
            scale = d.length() / boardW
            val mid = (a + b) * 0.5f
            calibrated = true
            place(Pose(mid + q * Vector3(0f, 0f, -halfH * scale), q))
            savedCalibration = rootPose to scale
        }

        /// Dibuja al otro jugador con su pose (PoseFrame), o lo oculta (null).
        fun drawAvatar(f: ByteArray?, me: Int) {
            val ok = f != null && f.size == POSE_LENGTH && (f[0].toInt() and 0xFF) == POSE_HEADER &&
                (f[1].toInt() and 0xFF) != me
            if (!ok) {
                avatar.forEach { it.setComponent(Visible(false)) }
                return
            }
            f!!
            fun i16(at: Int) = ((f[at].toInt() and 0xFF) or (f[at + 1].toInt() shl 8)).toShort().toFloat()
            fun point(at: Int) = toWorld(fromBoardMm(i16(at), i16(at + 2), i16(at + 4)))
            val flags = f[29].toInt()
            val id = f[1].toInt() and 0xFF
            // Su color: el de su ficha (el espejo de la simulación es "el otro").
            val other = if (id == 1 || id == 2) id else 3 - me
            if (other != avatarColor) {
                avatarColor = other
                val c = if (other == 1) PIECE_X else PIECE_O
                listOf(0, 2, 3).forEach { avatar[it].setComponent(material(c)) }
            }
            val head = point(2)
            val fwd = rootPose.q * Vector3(f[8] / 127f, f[10] / 127f, -f[9] / 127f)
            show(avatar[0], head, true)
            show(avatar[1], head + fwd * 0.07f, true)
            show(avatar[2], point(11), flags and 1 != 0)
            show(avatar[3], point(17), flags and 2 != 0)
            show(avatar[4], point(23), flags and 4 != 0 && !clawMode())
        }

        private fun show(e: Entity, world: Vector3, visible: Boolean) {
            e.setComponent(Visible(visible))
            if (visible) e.setComponent(Transform(Pose(world, Quaternion())))
        }

        private fun worldSphere(r: Float, color: Color4) = Entity.create(
            listOf(
                Mesh(Uri.parse("mesh://sphere")),
                Sphere(r),
                material(color),
                Transform(Pose(Vector3(0f, -10f, 0f), Quaternion())),
                Visible(false),
            ),
        )

        fun grabbed(e: Entity) = e.getComponent<IsdkGrabbable>().grabState == IsdkGrabState.Grabbed

        fun setGrabbable(e: Entity, on: Boolean) {
            val g = e.getComponent<IsdkGrabbable>()
            if (g.enabled != on) e.setComponent(g.apply { enabled = on })
        }

        fun setLocal(e: Entity, l: Vector3) = e.setComponent(Transform(Pose(l, Quaternion())))
        fun setWorld(e: Entity, l: Vector3) = e.setComponent(Transform(Pose(toWorld(l), rootPose.q)))

        /// Ligas de la resortera: de cada punta de la horquilla a la pelota.
        fun drawBands(ballLocal: Vector3) {
            for (i in 0 until BAND_DOTS) {
                val t = (i + 1f) / (BAND_DOTS + 1)
                setLocal(bands[i], forkL + (ballLocal - forkL) * t)
                setLocal(bands[BAND_DOTS + i], forkR + (ballLocal - forkR) * t)
            }
        }

        /// Puntos de la trayectoria prevista (null = ocultos).
        fun drawTrajectory(p0: Vector3?, v: Vector3, tLand: Float) {
            for ((i, d) in dots.withIndex()) {
                if (p0 == null) {
                    d.setComponent(Visible(false))
                    continue
                }
                val t = tLand * (i + 1) / TRAJ_DOTS
                setLocal(d, p0 + v * t + Vector3(0f, -GRAVITY * t * t / 2, 0f))
                d.setComponent(Visible(true))
            }
        }

        fun drawStick(knobLocal: Vector3) {
            val base = Vector3(knobRest.x, 0.015f, knobRest.z)
            for ((i, s) in stick.withIndex()) setLocal(s, base + (knobLocal - base) * ((i + 0.5f) / STICK_DOTS))
        }

        fun showHole(cell: Int, on: Boolean) {
            if (cell in 0 until 9) holes[cell].setComponent(Visible(on))
        }

        fun showLanding(l: Vector3?) {
            landing.setComponent(Visible(l != null))
            if (l != null) setLocal(landing, Vector3(l.x, 0.004f, l.z))
        }

        /// Confeti desde el centro del tablero.
        fun burst() {
            for (i in 0 until CONFETTI) {
                confettiPos[i] = Vector3(0f, 0.05f, 0f)
                confettiVel[i] = Vector3(
                    (Random.nextFloat() - 0.5f) * 0.8f,
                    0.8f + Random.nextFloat() * 0.8f,
                    (Random.nextFloat() - 0.5f) * 0.8f,
                )
                confetti[i].setComponent(Visible(true))
            }
            confettiLeft = 2.5f
        }

        fun animate(dt: Float) {
            if (confettiLeft <= 0f) return
            confettiLeft -= dt
            for (i in 0 until CONFETTI) {
                confettiVel[i] = confettiVel[i] + Vector3(0f, -1.5f * dt, 0f)
                confettiPos[i] = confettiPos[i] + confettiVel[i] * dt
                setLocal(confetti[i], confettiPos[i])
                if (confettiLeft <= 0f) confetti[i].setComponent(Visible(false))
            }
        }

        fun update() {
            val mine = canMove()
            val claw = clawMode()
            setLocal(head, local(num("posX"), num("posY"), headLift) + headSway)

            // Barra de tiempo (solo en turno); roja en los últimos 5 s.
            val left = num("timeLeft")
            val frac = if (int("phase") == 1) (left / num("turnSeconds", 20f)).coerceIn(0.001f, 1f) else 0.001f
            val bar = (frac * 100).toInt() + if (left <= 5f) 1000 else 0
            if (bar != barLook) {
                val warn = left <= 5f
                if ((barLook >= 1000) != warn || barLook < 0) timeBar.setComponent(material(if (warn) MISSED else PLACED))
                barLook = bar
                timeBar.setComponent(Scale(Vector3(frac, 1f, 1f)))
            }
            target.setComponent(Transform(Pose(local(num("refX"), num("refY"), 0.004f), Quaternion())))

            val bl = if (mine) 1 else 0
            if (bl != baseLook) {
                baseLook = bl
                base.setComponent(material(if (mine) BASE_MINE else BASE_LOCKED))
            }
            val ml = if (claw) 1 else 0
            if (ml != modeLook) {
                modeLook = ml
                slingParts.forEach { it.setComponent(Visible(!claw)) }
                clawParts.forEach { it.setComponent(Visible(claw)) }
                headLift = if (claw) CLAW_UP else HEAD_LIFT
                if (claw) drawTrajectory(null, Vector3(0f), 0f)
            }

            val board = game["board"] as? List<*>
            val ir = int("irCell", -1)
            val last = int("lastCell", -1)
            val lastResult = int("lastResult")
            drawWinLine(board)
            for (c in 0 until 9) {
                // Piezas: 0 vacía, 1 X, 2 O.
                val p = (board?.getOrNull(c) as? Number)?.toInt() ?: 0
                if (p != pieceLook[c]) {
                    pieceLook[c] = p
                    pieces[c].first.setComponent(Visible(p == 1))
                    pieces[c].second.setComponent(Visible(p == 2))
                }
                // Baldosa: IR ahora > último tiro > nada.
                val look = when {
                    c == ir -> 1
                    c == last && lastResult == 1 -> 2
                    c == last && lastResult > 1 -> 3
                    else -> 0
                }
                if (look != tileLook[c]) {
                    tileLook[c] = look
                    tiles[c].setComponent(Visible(look != 0))
                    if (look != 0) tiles[c].setComponent(material(when (look) { 1 -> IR; 2 -> PLACED; else -> MISSED }))
                }
            }
        }

        /// Resalta el tres en línea del ganador, si lo hay.
        private fun drawWinLine(board: List<*>?) {
            val w = int("winner")
            val cells = if (w == 1 || w == 2) {
                WIN_LINES.firstOrNull { l -> l.all { (board?.getOrNull(it) as? Number)?.toInt() == w } }
            } else {
                null
            }
            val look = cells?.joinToString() ?: ""
            if (look == winLook) return
            winLook = look
            if (cells == null) {
                winDots.forEach { it.setComponent(Visible(false)) }
                return
            }
            val a = local(geo.cellX(cells[0]), geo.cellY(cells[0]), 0.035f)
            val b = local(geo.cellX(cells[2]), geo.cellY(cells[2]), 0.035f)
            for ((i, d) in winDots.withIndex()) {
                setLocal(d, a + (b - a) * (i.toFloat() / (WIN_DOTS - 1)))
                d.setComponent(Visible(true))
            }
        }

        private fun piece(name: String, at: Vector3, size: Float) = Entity.create(
            listOf(
                Mesh(Uri.parse("apk:///models/$name.glb")),
                Transform(Pose(at + Vector3(0f, 0.004f, 0f), Quaternion())),
                Scale(Vector3(size)),
                TransformParent(root),
                Visible(false),
            ),
        )

        /// Esfera que se agarra con la mano (palma o pellizco).
        private fun grabbable(world: Vector3, r: Float, color: Color4, movement: IsdkGrabMovementType) = Entity.create(
            listOf(
                Mesh(Uri.parse("mesh://sphere")),
                Sphere(r),
                material(color),
                Transform(Pose(world, rootPose.q)),
                IsdkSphereCollider(r + 0.015f),
                IsdkGrabbable(enabled = false, movementType = movement),
                IsdkGrabFilter(
                    handContactPinch = true,
                    handDistancePinch = false,
                    handContactPalm = true,
                    handDistancePalm = false,
                ),
            ),
        )

        private fun box(parent: Entity, min: Vector3, max: Vector3, color: Color4, vararg more: ComponentBase): Entity =
            Entity.create(
                listOf(
                    Mesh(Uri.parse("mesh://box")),
                    Box(min, max),
                    material(color),
                    Transform(Pose(Vector3(0f, 0f, 0f), Quaternion())),
                    TransformParent(parent),
                ) + more,
            )

        private fun sphere(parent: Entity, at: Vector3, r: Float, color: Color4, vararg more: ComponentBase): Entity =
            Entity.create(
                listOf(
                    Mesh(Uri.parse("mesh://sphere")),
                    Sphere(r),
                    material(color),
                    Transform(Pose(at, Quaternion())),
                    TransformParent(parent),
                ) + more,
            )
    }

    // --- Interacción ------------------------------------------------------------

    /// Qué pasa con el tiro en curso.
    private enum class Shot { IDLE, AIMING, FLYING, WAITING, SINKING, BOUNCING }

    /// Cada cuadro: coloca la mesa frente al usuario, arma el tablero cuando
    /// llega la geometría, atiende la resortera o la grúa y actualiza todo.
    private inner class GameSystem : SystemBase() {
        private var lastSentAt = 0L
        private var lastFrame = 0L
        private var statusText = ""

        // Tiro (los dos modos): se manda "fire" y se espera a que la ESP pase
        // por RESOLVIENDO (fase 2) y salga con el resultado.
        private var shot = Shot.IDLE
        private var firedAt = 0L
        private var sawResolving = false
        private var animStart = 0L
        private var resultCell = -1

        // Resortera: pelota en coordenadas de la mesa.
        private var ballPos = Vector3(0f)
        private var ballVel = Vector3(0f)

        // Grúa: hacia dónde va la cabeza, en mm.
        private var clawX = Float.NaN
        private var clawY = Float.NaN
        private var buttonDown = false
        private var remoteClawDown = false
        private var lastPoseAt = 0L

        // Avisos y sonidos según cambia el estado.
        private var prevPhase = -1
        private var prevMine = false
        private var prevTick = -1
        private var hadRemote = false
        private var note = ""

        override fun execute() {
            // Antes de rastrear el visor reporta la cabeza en el piso: se espera.
            val viewer = scene.getViewerPose()
            if (viewer.t.y < MIN_HEAD_HEIGHT) return
            // Calibrada, la mesa se queda donde la pusiste.
            if (!scene3d.calibrated && (!scene3d.placed || viewer.t.distanceTo(scene3d.rootPose.t) > 2f)) {
                placeInFront(viewer)
            }

            val g = geometry()
            if (g != null && !scene3d.built) scene3d.build(g)
            updateStatus()
            if (!scene3d.built || g == null) return

            val now = System.nanoTime()
            val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1e9f, 0.05f)
            lastFrame = now

            if (calibToggle) {
                calibToggle = false
                scene3d.toggleCalibration()
                val label = if (scene3d.calibrating) "Listo" else "Calibrar"
                calibButton?.let { b -> b.post { b.text = label } }
            }
            sendPose(viewer, now)
            val remote = remotePose.takeIf { now - remoteAt < REMOTE_STALE_NS }
            scene3d.drawAvatar(remote, int("me"))
            remoteClawDown = remote != null && remote.size == POSE_LENGTH && remote[29].toInt() and 8 != 0
            if (remote != null) hadRemote = true
            note = if (hadRemote && remote == null) "El otro visor no envía su posición" else ""
            events()
            scene3d.animate(dt)

            trackResult(now)
            // Lo del otro modo está oculto: que tampoco se pueda agarrar.
            if (clawMode()) {
                scene3d.setGrabbable(scene3d.ball, false)
                claw(g, dt, now)
            } else {
                scene3d.setGrabbable(scene3d.knob, false)
                scene3d.setGrabbable(scene3d.button, false)
                slingshot(g, dt, now)
            }
            scene3d.update()
        }

        // --- Resultado del tiro ------------------------------------------------------

        /// Sonidos de turno, cuenta regresiva y fin de partida; confeti al ganar.
        private fun events() {
            val phase = int("phase")
            val mine = canMove()
            if (mine && !prevMine) sfx?.play(Sfx.Sound.TURN)
            val tick = if (mine) num("timeLeft").toInt() else -1
            if (mine && tick in 0..4 && tick != prevTick) sfx?.play(Sfx.Sound.TICK)
            if (phase == 3 && prevPhase != 3 && prevPhase != -1) {
                val w = int("winner")
                if (w == 1 || w == 2) {
                    sfx?.play(Sfx.Sound.WIN)
                    scene3d.burst()
                } else {
                    sfx?.play(Sfx.Sound.DRAW)
                }
            }
            prevPhase = phase
            prevMine = mine
            prevTick = tick
        }

        private fun fire(now: Long) {
            PanelBus.send(this@GameActivity3D, "fire")
            firedAt = now
            sawResolving = false
        }

        /// Tras tirar: espera la fase 2 y luego el resultado. Si la ESP no
        /// aceptó el tiro (no llegó a fase 2) o tarda demasiado, se cancela.
        private fun trackResult(now: Long) {
            if (shot != Shot.FLYING && shot != Shot.WAITING) return
            val phase = int("phase")
            val ms = (now - firedAt) / 1_000_000
            if (phase == 2) sawResolving = true
            when {
                sawResolving && phase != 2 -> if (shot == Shot.WAITING) finishShot(now)
                !sawResolving && ms > FIRE_ACCEPT_MS -> reset()
                ms > FIRE_TIMEOUT_MS -> reset()
            }
        }

        private fun finishShot(now: Long) {
            val placed = int("lastResult") == 1
            resultCell = int("lastCell", -1)
            animStart = now
            if (clawMode()) {
                shot = Shot.SINKING // la garra sube de regreso
                return
            }
            if (placed && resultCell >= 0) {
                scene3d.showHole(resultCell, true)
                sfx?.play(Sfx.Sound.HOLE)
                shot = Shot.SINKING
            } else {
                // Rebota: sale hacia arriba y a un lado.
                val side = if (ballPos.x >= 0) 1f else -1f
                ballVel = Vector3(side * 0.5f, 1.1f, -0.25f)
                shot = Shot.BOUNCING
                sfx?.play(Sfx.Sound.BOUNCE)
            }
        }

        private fun reset() {
            if (resultCell >= 0) scene3d.showHole(resultCell, false)
            resultCell = -1
            shot = Shot.IDLE
            scene3d.headLift = if (clawMode()) CLAW_UP else HEAD_LIFT
        }

        // --- Resortera ------------------------------------------------------------

        private fun slingshot(g: Geometry, dt: Float, now: Long) {
            val s = scene3d
            s.headSway = Vector3(0f)
            val mine = canMove()
            s.setGrabbable(s.ball, mine && (shot == Shot.IDLE || shot == Shot.AIMING))
            when (shot) {
                Shot.IDLE -> {
                    if (mine && s.grabbed(s.ball)) {
                        shot = Shot.AIMING
                    } else {
                        s.setWorld(s.ball, s.slingRest)
                        s.drawBands(s.slingRest)
                        s.drawTrajectory(null, Vector3(0f), 0f)
                    }
                }
                Shot.AIMING -> {
                    val (p0, v) = launch(s.toLocal(s.ball.getComponent<Transform>().transform.t))
                    val pulled = (s.slingRest - p0).length() > MIN_PULL
                    if (s.grabbed(s.ball) && mine) {
                        s.drawBands(p0)
                        val tLand = landingTime(p0, v)
                        s.drawTrajectory(if (pulled) p0 else null, v, tLand)
                        s.showLanding(if (pulled) p0 + v * tLand else null)
                        if (pulled) sendMove(g, p0 + v * tLand, now)
                    } else if (pulled && mine) {
                        // Soltó: la cabeza ya va al punto de caída; vuela y se tira.
                        sendMove(g, p0 + v * landingTime(p0, v), now, force = true)
                        fire(now)
                        sfx?.play(Sfx.Sound.LAUNCH)
                        s.showLanding(null)
                        ballPos = p0
                        ballVel = v
                        s.drawTrajectory(null, v, 0f)
                        shot = Shot.FLYING
                    } else {
                        shot = Shot.IDLE
                    }
                }
                Shot.FLYING, Shot.BOUNCING -> {
                    ballPos += ballVel * dt
                    ballVel += Vector3(0f, -GRAVITY * dt, 0f)
                    if (shot == Shot.FLYING) {
                        s.drawBands(s.slingRest)
                        if (ballPos.y <= BALL_R) {
                            ballPos = Vector3(ballPos.x, BALL_R, ballPos.z)
                            shot = Shot.WAITING
                        }
                    } else if (now - animStart > BOUNCE_NS) {
                        reset()
                    }
                    s.setWorld(s.ball, ballPos)
                }
                Shot.WAITING -> s.setWorld(s.ball, ballPos)
                Shot.SINKING -> {
                    // Rueda al centro del hueco y se hunde.
                    val t = (now - animStart) / 1e9f
                    val hole = s.local(g.cellX(resultCell), g.cellY(resultCell), BALL_R)
                    val p = if (t < 0.3f) ballPos + (hole - ballPos) * (t / 0.3f) else hole - Vector3(0f, (t - 0.3f) * 0.12f, 0f)
                    s.setWorld(s.ball, p)
                    if (t > 1.2f) reset()
                }
            }
        }

        /// Pelota jalada hasta [ball] (local) -> punto de salida y velocidad.
        private fun launch(ball: Vector3): Pair<Vector3, Vector3> {
            val rest = scene3d.slingRest
            var pull = rest - ball
            if (pull.length() > MAX_PULL) pull = pull.normalize() * MAX_PULL
            return (rest - pull) to (pull * LAUNCH_K)
        }

        /// Cuándo toca la mesa (y = radio) un tiro desde [p0] con [v].
        private fun landingTime(p0: Vector3, v: Vector3): Float {
            val h = p0.y - BALL_R
            return (v.y + sqrt(v.y * v.y + 2 * GRAVITY * maxOf(h, 0f))) / GRAVITY
        }

        // --- Grúa -------------------------------------------------------------------

        private fun claw(g: Geometry, dt: Float, now: Long) {
            val s = scene3d
            val mine = canMove()
            val idle = shot == Shot.IDLE
            s.setGrabbable(s.knob, mine && idle)
            s.setGrabbable(s.button, mine && idle)
            if (!mine || clawX.isNaN()) {
                clawX = num("refX")
                clawY = num("refY")
            }

            // Joystick: la inclinación de la perilla da la velocidad de la cabeza.
            val knobLocal = s.toLocal(s.knob.getComponent<Transform>().transform.t)
            if (mine && idle && s.grabbed(s.knob)) {
                var off = Vector3(knobLocal.x - s.knobRest.x, 0f, knobLocal.z - s.knobRest.z)
                if (off.length() > JOY_R) off = off.normalize() * JOY_R
                clawX = (clawX + off.x / JOY_R * JOY_SPEED * dt).coerceIn(g.xMin, g.xMax)
                clawY = (clawY - off.z / JOY_R * JOY_SPEED * dt).coerceIn(g.yMin, g.yMax)
                sendMoveMm(clawX, clawY, now)
                s.drawStick(s.knobRest + off)
            } else {
                s.setWorld(s.knob, s.knobRest)
                s.drawStick(s.knobRest)
            }

            // Botón: agarrarlo o meter la mano en él lo aprieta.
            val pressed = s.grabbed(s.button) || handNear(s.toWorld(s.buttonAt), BUTTON_REACH)
            s.setWorld(s.button, s.buttonAt - Vector3(0f, if (pressed) 0.01f else 0f, 0f))
            if (pressed && !buttonDown && mine && idle) {
                // Como una grúa de verdad, no cae exactamente donde apuntaste.
                val wobble = { (Random.nextFloat() - 0.5f) * 2 * CLAW_WOBBLE_MM }
                sendMoveMm((clawX + wobble()).coerceIn(g.xMin, g.xMax), (clawY + wobble()).coerceIn(g.yMin, g.yMax), now, force = true)
                fire(now)
                sfx?.play(Sfx.Sound.CLAW)
                animStart = now
                shot = Shot.FLYING
            }
            buttonDown = pressed

            // Garra: baja al tirar, espera abajo el resultado y sube; al bajar se
            // bambolea un poco y se va calmando.
            val t = (now - animStart) / 1e9f
            s.headSway = if (shot == Shot.FLYING || shot == Shot.WAITING) {
                Vector3(sin(t * 14f) * 0.005f, 0f, cos(t * 11f) * 0.004f) * exp(-1.5f * t)
            } else {
                Vector3(0f)
            }
            s.headLift = when (shot) {
                Shot.FLYING, Shot.WAITING -> {
                    if (t >= CLAW_DROP_S) shot = Shot.WAITING
                    CLAW_UP + (CLAW_DOWN - CLAW_UP) * min(t / CLAW_DROP_S, 1f)
                }
                Shot.SINKING -> {
                    if (t >= CLAW_DROP_S) reset()
                    CLAW_DOWN + (CLAW_UP - CLAW_DOWN) * min(t / CLAW_DROP_S, 1f)
                }
                // El otro jugador soltó su garra: también se ve bajar aquí.
                else -> if (remoteClawDown) CLAW_DOWN else CLAW_UP
            }
        }

        /// Posición de cada mano rastreada (izquierda, derecha; null si no se ve).
        private fun hands(): List<Vector3?> {
            val body = Query.where { has(AvatarBody.id) }.eval()
                .filter { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
                .firstOrNull()?.getComponent<AvatarBody>() ?: return listOf(null, null)
            return listOf(body.leftHand, body.rightHand).map { h ->
                val c = h.tryGetComponent<Controller>()
                val p = h.tryGetComponent<Transform>()?.transform
                if (c != null && p != null && c.isActive && c.type == ControllerType.HAND) p.t else null
            }
        }

        /// ¿Alguna mano rastreada a menos de [r] m de [world]?
        private fun handNear(world: Vector3, r: Float) = hands().any { it != null && it.distanceTo(world) < r }

        /// Manda tu cabeza, manos y pelota al otro visor (PoseFrame, 15 Hz).
        private fun sendPose(viewer: Pose, now: Long) {
            if (now - lastPoseAt < POSE_EVERY_NS || game["connected"] != true) return
            lastPoseAt = now
            val s = scene3d
            val f = ByteArray(POSE_LENGTH)
            f[0] = POSE_HEADER.toByte()
            f[1] = int("me").toByte()
            fun put(at: Int, world: Vector3) {
                val (x, y, h) = s.toBoardMm(s.toLocal(world))
                for ((i, v) in listOf(x, y, h).withIndex()) {
                    val n = v.toInt().coerceIn(-32768, 32767)
                    f[at + 2 * i] = n.toByte()
                    f[at + 2 * i + 1] = (n shr 8).toByte()
                }
            }
            put(2, viewer.t)
            val d = s.rootPose.q.inverse() * viewer.forward()
            f[8] = (d.x * 127).toInt().toByte()
            f[9] = (-d.z * 127).toInt().toByte()
            f[10] = (d.y * 127).toInt().toByte()
            var flags = 0
            val (left, right) = hands()
            left?.let { put(11, it); flags = flags or 1 }
            right?.let { put(17, it); flags = flags or 2 }
            if (!clawMode()) {
                put(23, s.ball.getComponent<Transform>().transform.t)
                flags = flags or 4
            } else if (shot == Shot.FLYING || shot == Shot.WAITING) {
                flags = flags or 8
            }
            f[29] = flags.toByte()
            PanelBus.send(this@GameActivity3D, "pose", mapOf("data" to f))
        }

        // --- Envío ------------------------------------------------------------------

        private fun sendMove(g: Geometry, landLocal: Vector3, now: Long, force: Boolean = false) {
            val (x, y) = scene3d.toMm(landLocal)
            sendMoveMm(x.coerceIn(g.xMin, g.xMax), y.coerceIn(g.yMin, g.yMax), now, force)
        }

        private fun sendMoveMm(x: Float, y: Float, now: Long, force: Boolean = false) {
            if (!force && now - lastSentAt < SEND_EVERY_NS) return
            lastSentAt = now
            PanelBus.send(this@GameActivity3D, "move", mapOf("x" to x.toDouble(), "y" to y.toDouble()))
        }

        private fun placeInFront(viewer: Pose) {
            // A 50 cm al frente, a la altura de una mesa (45 cm bajo los ojos).
            val f = viewer.forward()
            var flat = Vector3(f.x, 0f, f.z)
            if (flat.length() < 1e-3f) flat = Vector3(0f, 0f, -1f)
            flat = flat.normalize()
            val pos = viewer.t + flat * 0.55f + Vector3(0f, -0.45f, 0f)
            val toViewer = -flat
            val a = atan2(toViewer.x, toViewer.z)
            scene3d.place(Pose(pos, Quaternion(cos(a / 2), 0f, sin(a / 2), 0f)))
        }

        private fun updateStatus() {
            val base = (game["status"] as? String) ?: "Esperando datos del panel 2D…"
            val score = (game["score"] as? String)?.let { "\n$it" } ?: ""
            val text = base + score + if (note.isEmpty()) "" else "\n$note"
            if (text == statusText) return
            statusText = text
            status?.let { tv -> tv.post { tv.text = text } }
        }
    }

    private fun material(c: Color4) = Material().apply {
        baseColor = c
        roughness = 0.5f
    }

    private companion object {
        const val BOARD_M = 0.42f          // ancho del tablero virtual
        const val HEAD_R = 0.018f          // m, esfera de la cabeza
        const val HEAD_LIFT = 0.03f        // m sobre la mesa (resortera)
        const val SEND_EVERY_NS = 50_000_000L  // 20 movimientos/s, como la UART
        const val MIN_HEAD_HEIGHT = 0.3f

        // Resortera. La gravedad es menor a la real para que el vuelo se vea.
        const val SLING_DIST = 0.1f        // m del borde de la mesa a la resortera
        const val SLING_H = 0.12f          // m, altura de la pelota en reposo
        const val BALL_R = 0.02f
        const val MAX_PULL = 0.25f         // m que se puede jalar la liga
        const val MIN_PULL = 0.03f         // menos que esto no cuenta como tiro
        const val LAUNCH_K = 6.5f          // (m/s) por m jalado; ayuda a llegar a las filas lejanas
        const val GRAVITY = 2.5f           // m/s²; arco un poco más largo para cubrir las 9 casillas
        const val BAND_DOTS = 5
        const val TRAJ_DOTS = 14
        const val BOUNCE_NS = 1_500_000_000L

        // Grúa.
        const val JOY_H = 0.07f            // m, perilla sobre la base
        const val JOY_R = 0.05f            // m de inclinación para velocidad máxima
        const val JOY_SPEED = 60f          // mm/s a fondo
        const val STICK_DOTS = 6
        const val BUTTON_REACH = 0.045f    // m de la mano al botón para apretarlo
        const val CLAW_UP = 0.12f
        const val CLAW_DOWN = 0.03f
        const val CLAW_DROP_S = 0.8f
        const val CLAW_WOBBLE_MM = 4f      // la garra cae hasta ±4 mm de donde apuntaste

        // Calibración y el otro jugador.
        const val MARK_R = 0.015f
        const val POSE_HEADER = 0xD0
        const val POSE_LENGTH = 30
        const val POSE_EVERY_NS = 66_000_000L     // 15 Hz
        const val REMOTE_STALE_NS = 1_000_000_000L // sin poses 1 s: se oculta

        // Tras tirar: la ESP debe pasar a RESOLVIENDO pronto y resolver en
        // su resolverMax (12 s) más el reposo.
        const val FIRE_ACCEPT_MS = 2000L
        const val FIRE_TIMEOUT_MS = 16000L

        val BASE_LOCKED = Color4(0.20f, 0.17f, 0.19f, 1f)
        val BASE_MINE = Color4(0.42f, 0.10f, 0.24f, 1f)      // tu turno: rosa oscuro
        val GRID = Color4(0.85f, 0.78f, 0.80f, 1f)
        val IR = Color4(0.20f, 0.75f, 0.85f, 1f)
        val PLACED = Color4(0.30f, 0.75f, 0.40f, 1f)
        val MISSED = Color4(0.85f, 0.25f, 0.25f, 1f)
        val HOLE = Color4(0.02f, 0.02f, 0.02f, 1f)
        val HEAD = Color4(1.0f, 0.62f, 0.18f, 1f)
        val CLAW = Color4(0.75f, 0.75f, 0.80f, 1f)
        val WOOD = Color4(0.55f, 0.36f, 0.20f, 1f)
        val BAND = Color4(0.85f, 0.15f, 0.15f, 1f)
        val BALL = Color4(0.95f, 0.20f, 0.20f, 1f)
        val CONSOLE = Color4(0.15f, 0.15f, 0.18f, 1f)
        val BUTTON = Color4(0.95f, 0.10f, 0.20f, 1f)
        val WHITE = Color4(1f, 1f, 1f, 1f)
        val GOLD = Color4(1.0f, 0.80f, 0.15f, 1f)
        val CONFETTI_COLORS = listOf(GOLD, Color4(0.2f, 0.8f, 1f, 1f), Color4(1f, 0.3f, 0.6f, 1f), Color4(0.4f, 1f, 0.4f, 1f))
        const val CONFETTI = 40
        const val WIN_DOTS = 9
        val WIN_LINES = listOf(
            listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
            listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
            listOf(0, 4, 8), listOf(2, 4, 6),
        )

        /// Calibración de esta sesión de la app (mesa y escala). Se pierde al
        /// cerrar la app: el visor cambia de coordenadas entre sesiones.
        var savedCalibration: Pair<Pose, Float>? = null
        val PIECE_X = Color4(0.74f, 0.0f, 0.29f, 1f)        // rosa del tema
        val PIECE_O = Color4(0.95f, 0.85f, 0.90f, 1f)
        val MARK_A = Color4(0.10f, 0.85f, 0.95f, 1f)
        val MARK_B = Color4(1.0f, 0.85f, 0.10f, 1f)
    }
}
