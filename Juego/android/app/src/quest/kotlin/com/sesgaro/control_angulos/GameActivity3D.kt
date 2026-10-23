// Rastreo de cuerpo (articulaciones de los dedos) es API experimental del Spatial SDK.
@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)

package com.sesgaro.control_angulos

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.ControllerPose
import com.meta.spatial.runtime.JointType
import com.meta.spatial.runtime.SkeletonJoint
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarSystem
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
import kotlin.math.abs
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
/// Dos visores: aprietan Calibrar a la vez, la ESP lleva la cabeza real a dos
/// puntos y cada uno la toca con el índice; con esos dos toques cada visor
/// acomoda su mesa sobre la máquina (ver GameSystem.calibrate). Así los dos miden en mm de la
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
            // La ESP reenvía las poses de los dos jugadores: la tuya se ignora
            // (si no, tapaba la del otro y el personaje parpadeaba).
            "poses" -> (args["data"] as? ByteArray)?.takeIf { it.size > 1 && (it[1].toInt() and 0xFF) != int("me") }?.let {
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
        if (checkSelfPermission(BODY_TRACKING) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(BODY_TRACKING), 1)
        }
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
        // Las patas de gato reemplazan a las manos transparentes del sistema.
        systemManager.findSystem<AvatarSystem>().setShowHands(false)
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
        /// Pelota "al aire" sobre la cabeza durante la calibración: lo que hay que tocar.
        lateinit var markA: Entity
        lateinit var calibBtn: Entity
        lateinit var calibAt: Vector3
        var boardW = 0f

        // El otro jugador: personaje gato (cabeza, patas) del color de su ficha.
        private val avatar = mutableListOf<Entity>()
        private val faces = mutableListOf<Entity>()

        // Patas de gato en tus manos (izquierda, derecha): models/pata.glb, con
        // 2 huesos (palma, dedos) horneados en PAW_FRAMES cuadros de abierta a
        // cerrada (pata_{x,o}_N.glb, ver tool/pata_a_glb.py): se ve uno a la vez.
        private val paws = List(2) { mutableListOf<Entity>() }
        private val pawFrame = intArrayOf(-1, -1)
        private var pawColor = -1
        private val pawCurl = FloatArray(2)
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
                // Fichas: pieza_x/pieza_o (1 m de ancho, ver tool/modelos_a_glb.py).
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

            markA = worldSphere(MARK_R, MARK_A)
            // Botón cian a la derecha de tu lado: pide calibrar (o cancela),
            // igual que el botón Calibrar del letrero, apretándolo con la mano.
            calibAt = Vector3(w / 2 + 0.12f, 0.02f, h / 2 - 0.03f)
            calibBtn = grabbable(toWorld(calibAt), 0.02f, MARK_A, IsdkGrabMovementType.None)

            // Otro jugador: cabeza, patas izquierda y derecha, pelota.
            avatar += model("cabeza_x", HEAD_SIZE)
            avatar += model("pata_x_0", 1f)
            avatar += model("pata_x_0", 1f)
            avatar += worldSphere(BALL_R, BALL)
            for (i in 1..FACES) faces += model("cara_$i", HEAD_SIZE)
            for (hand in paws) repeat(PAW_FRAMES) { hand += model("pata_x_$it", 1f) }

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

        /// Resortera al centro del borde de tu lado: modelo slingshot.glb, ligas y pelota.
        private fun buildSlingshot(h: Float) {
            // El modelo (Blender, Y arriba) mide ~7.9 unidades de alto: se escala
            // y se corre para que el centro de las puntas quede en x = 0.
            val z = h / 2 + SLING_DIST
            val origin = Vector3(-SLING_FORK_MID.x * SLING_SCALE, 0f, z - SLING_FORK_MID.z * SLING_SCALE)
            forkL = origin + SLING_TIP_L * SLING_SCALE
            forkR = origin + SLING_TIP_R * SLING_SCALE
            slingRest = (forkL + forkR) * 0.5f - Vector3(0f, 0.02f, 0f)
            slingParts += Entity.create(
                listOf(
                    Mesh(Uri.parse("apk:///models/slingshot.glb")),
                    Transform(Pose(origin, Quaternion())),
                    Scale(Vector3(SLING_SCALE)),
                    TransformParent(root),
                ),
            )
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

        /// Acomoda la mesa con los dos toques de la calibración: [world] es la
        /// punta de tu índice al tocar la cabeza y [mm] dónde estaba la cabeza
        /// (x, y de la máquina). Dos puntos dan el giro, la escala y la posición.
        fun applyCalibration(world: List<Vector3>, mm: List<Pair<Float, Float>>) {
            val la = local(mm[0].first, mm[0].second)
            val lb = local(mm[1].first, mm[1].second)
            val dl = lb - la
            val dw = Vector3(world[1].x - world[0].x, 0f, world[1].z - world[0].z)
            if (dw.length() < 0.05f || dl.length() < 1e-4f) return // toques encimados: se ignora
            val yaw = atan2(-dw.z, dw.x) - atan2(-dl.z, dl.x)
            val q = Quaternion(cos(yaw / 2), 0f, sin(yaw / 2), 0f)
            scale = dw.length() / dl.length()
            // El dedo toca arriba de la cabeza real, HEAD_TOUCH_M sobre la mesa.
            val up = Vector3(0f, HEAD_TOUCH_M, 0f)
            val t = (world[0] - up - q * (la * scale) + world[1] - up - q * (lb * scale)) * 0.5f
            calibrated = true
            place(Pose(t, q))
            savedCalibration = rootPose to scale
        }

        /// Dibuja al otro jugador con su pose (PoseFrame), o lo oculta (null).
        /// [face]: expresión (1..4, ver tool/cabeza_a_glb.py) del jugador 1 o 2.
        fun drawAvatar(f: ByteArray?, me: Int, face: (Int) -> Int) {
            val ok = f != null && f.size == POSE_LENGTH && (f[0].toInt() and 0xFF) == POSE_HEADER &&
                (f[1].toInt() and 0xFF) != me
            if (!ok) {
                (avatar + faces).forEach { it.setComponent(Visible(false)) }
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
                avatar[0].setComponent(Mesh(Uri.parse("apk:///models/cabeza_${tag(other)}.glb")))
                avatar[1].setComponent(Mesh(Uri.parse("apk:///models/pata_${tag(other)}_0.glb")))
                avatar[2].setComponent(Mesh(Uri.parse("apk:///models/pata_${tag(other)}_0.glb")))
            }
            val head = point(2)
            val fwd = rootPose.q * Vector3(f[8] / 127f, f[10] / 127f, -f[9] / 127f)
            // Solo el giro horizontal: la cabeza (cara hacia -Z) mira a donde mira él.
            val yaw = atan2(-fwd.x, -fwd.z)
            val q = Quaternion(cos(yaw / 2), 0f, sin(yaw / 2), 0f)
            show(avatar[0], head, true, q)
            val expr = face(other)
            for ((i, e) in faces.withIndex()) show(e, head, i + 1 == expr, q)
            show(avatar[1], point(11), flags and 1 != 0, q)
            show(avatar[2], point(17), flags and 2 != 0, q)
            show(avatar[3], point(23), flags and 4 != 0 && !clawMode())
        }

        private fun tag(player: Int) = if (player == 1) "x" else "o"

        /// Modelo de models/ suelto en el mundo, oculto hasta que se use.
        private fun model(name: String, size: Float, vararg more: ComponentBase) = Entity.create(
            listOf(
                Mesh(Uri.parse("apk:///models/$name.glb")),
                Transform(Pose(Vector3(0f, -10f, 0f), Quaternion())),
                Scale(Vector3(size)),
                Visible(false),
            ) + more,
        )

        /// La animación "cerrar" dura 1 s: congelada en t = 0 (abierta) … 1 (cerrada).
        /// Pone cada pata en su mano y la cierra con ella (cuadro 0 abierta … último cerrada).
        fun drawPaws(hands: List<Pair<Pose, Float>?>, me: Int, dt: Float) {
            if (me != pawColor) {
                pawColor = me
                for (hand in paws) hand.forEachIndexed { f, e -> e.setComponent(Mesh(Uri.parse("apk:///models/pata_${tag(me)}_$f.glb"))) }
            }
            for ((i, h) in hands.withIndex()) {
                val frame = if (h == null) -1 else {
                    val target = h.second
                    pawCurl[i] += (target - pawCurl[i]) * min(1f, dt * 15f)
                    (pawCurl[i] * (PAW_FRAMES - 1) + 0.5f).toInt()
                }
                if (frame != pawFrame[i]) {
                    if (pawFrame[i] >= 0) paws[i][pawFrame[i]].setComponent(Visible(false))
                    if (frame >= 0) paws[i][frame].setComponent(Visible(true))
                    pawFrame[i] = frame
                }
                // La mano derecha viene volteada respecto a la izquierda: 180° más a lo largo.
                val rot = if (i == 1) RIGHT_FLIP * PAW_ROT else PAW_ROT
                if (h != null) paws[i][frame].setComponent(Transform(Pose(h.first.t, h.first.q * rot)))
            }
        }

        private fun show(e: Entity, world: Vector3, visible: Boolean, q: Quaternion = Quaternion()) {
            e.setComponent(Visible(visible))
            if (visible) e.setComponent(Transform(Pose(world, q)))
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

            val bl = if (calibrating) 2 else if (mine) 1 else 0
            if (bl != baseLook) {
                baseLook = bl
                base.setComponent(material(when (bl) { 2 -> BASE_CALIB; 1 -> BASE_MINE; else -> BASE_LOCKED }))
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
        private var calibDown = false
        private var frameHands: List<Pair<Pose, Float>?> = listOf(null, null)

        // Calibración tocando la cabeza real (la lleva la ESP a dos puntos).
        private val touchWorld = arrayOfNulls<Vector3>(2)
        private val touchMm = arrayOfNulls<Pair<Float, Float>>(2)
        private val tipHistory = ArrayDeque<Pair<Long, Vector3>>()
        private var prevCalib = 0
        private var calibAskedAt = 0L

        private fun calibLabel() {
            val label = if (scene3d.calibrating) "Cancelar" else "Calibrar"
            calibButton?.let { b -> b.post { b.text = label } }
        }

        /// ¿[p] lleva [STILL_NS] sin moverse más de [tol]? [h]: historial (tiempo, posición).
        private fun still(h: ArrayDeque<Pair<Long, Vector3>>, p: Vector3, now: Long, tol: Float): Boolean {
            h.addLast(now to p)
            while (h.size > 1 && now - h[1].first >= STILL_NS) h.removeFirst()
            return now - h.first().first >= STILL_NS && h.all { it.second.distanceTo(p) < tol }
        }

        /// Sigue la calibración de la ESP (calibStep): pinta la pelota sobre la
        /// cabeza y, cuando la cabeza llegó al punto, toma la punta de tu índice
        /// quieto como ese punto y avisa (touch). Al terminar, acomoda la mesa.
        private fun calibrate(now: Long) {
            val s = scene3d
            val step = int("calibStep")
            if (step != prevCalib) {
                if (prevCalib == 0) touchWorld.fill(null)
                if (prevCalib == 2 && step == 0 && touchWorld.all { it != null }) {
                    s.applyCalibration(touchWorld.map { it!! }, touchMm.map { it!! })
                    sfx?.play(Sfx.Sound.TURN)
                }
                prevCalib = step
                s.calibrating = step != 0
                s.markA.setComponent(Visible(step != 0))
                tipHistory.clear()
                calibLabel()
            }
            if (step == 0) return
            val x = num("posX")
            val y = num("posY")
            s.markA.setComponent(Transform(Pose(s.toWorld(s.local(x, y, HEAD_TOUCH_M / s.scale)), Quaternion())))
            val bit = 1 shl (int("me") - 1)
            val arrived = abs(x - num("refX")) < 3f && abs(y - num("refY")) < 3f
            val tip = pointingTip()
            if (int("calibWho") and bit == 0 || touchWorld[step - 1] != null || !arrived || tip == null) {
                tipHistory.clear()
                return
            }
            if (!still(tipHistory, tip, now, STILL_M)) return
            touchWorld[step - 1] = tip
            touchMm[step - 1] = num("refX") to num("refY")
            Log.d("Gato3D", "calibración punto $step: índice $tip, mano ${frameHands.map { it?.first?.t }}")
            sfx?.play(Sfx.Sound.TICK)
            PanelBus.send(this@GameActivity3D, "touch", mapOf("step" to step))
        }

        private fun calibNote(): String {
            val step = int("calibStep")
            if (step == 0) {
                return when {
                    System.nanoTime() - calibAskedAt >= CALIB_WAIT_NS -> ""
                    int("phase") == 1 || int("phase") == 2 -> "Para calibrar, espera a que termine la partida"
                    else -> "Esperando a que el otro jugador apriete Calibrar…"
                }
            }
            val bit = 1 shl (int("me") - 1)
            return when {
                int("calibWho") and bit == 0 -> "El otro jugador está calibrando"
                touchWorld[step - 1] != null -> "Punto $step listo. Esperando al otro jugador…"
                abs(num("posX") - num("refX")) >= 3f || abs(num("posY") - num("refY")) >= 3f -> "Calibrando: la cabeza va al punto $step de 2…"
                else -> "Calibrando (punto $step de 2): toca la cabeza con la punta del índice y quédate quieto"
            }
        }

        private var remoteClawDown = false
        private var lastPoseAt = 0L

        // Avisos y sonidos según cambia el estado.
        private var prevPhase = -1
        private var prevMine = false
        private var prevTick = -1
        private var resultAt = 0L
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
            frameHands = handStates()

            val calPressed = scene3d.grabbed(scene3d.calibBtn) || handNear(scene3d.toWorld(scene3d.calibAt), BUTTON_REACH)
            scene3d.setWorld(scene3d.calibBtn, scene3d.calibAt - Vector3(0f, if (calPressed) 0.01f else 0f, 0f))
            if (calPressed && !calibDown) calibToggle = true
            calibDown = calPressed
            if (calibToggle) {
                calibToggle = false
                calibAskedAt = if (scene3d.calibrating) 0L else System.nanoTime()
                PanelBus.send(this@GameActivity3D, "calibrate")
            }
            sendPose(viewer, now)
            val remote = remotePose.takeIf { now - remoteAt < REMOTE_STALE_NS }
            calibrate(now)
            scene3d.drawAvatar(remote, int("me"), ::face)
            remoteClawDown = remote != null && remote.size == POSE_LENGTH && remote[29].toInt() and 8 != 0
            if (remote != null) hadRemote = true
            val calibText = calibNote()
            note = when {
                calibText.isNotEmpty() -> calibText
                hadRemote && remote == null -> "El otro visor no envía su posición"
                else -> ""
            }
            events()
            scene3d.animate(dt)
            scene3d.drawPaws(frameHands, int("me"), dt)

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
        /// Expresión del personaje de [player]: 4 si acertó o ganó, 3 si perdió la
        /// partida, 2 si su último tiro falló (unos segundos), 1 si no.
        private fun face(player: Int): Int {
            if (int("phase") == 3) {
                val w = int("winner")
                return if (w == player) 4 else if (w == 1 || w == 2) 3 else 1
            }
            if (int("lastPlayer") == player && System.nanoTime() - resultAt < FACE_NS) {
                return if (int("lastResult") == 1) 4 else 2
            }
            return 1
        }

        private fun events() {
            val phase = int("phase")
            if (prevPhase == 2 && phase != 2) resultAt = System.nanoTime()
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

        /// Pose de cada mano rastreada (izquierda, derecha) y qué tan cerrada está
        /// (0 abierta … 1 puño); null si no se ve.
        private fun handStates(): List<Pair<Pose, Float>?> {
            val curls = handCurls()
            val body = Query.where { has(AvatarBody.id) }.eval()
                .filter { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
                .firstOrNull()?.getComponent<AvatarBody>() ?: return listOf(null, null)
            return listOf(body.leftHand, body.rightHand).mapIndexed { i, h ->
                val c = h.tryGetComponent<Controller>()
                val p = h.tryGetComponent<Transform>()?.transform
                if (c != null && p != null && c.isActive && c.type == ControllerType.HAND) {
                    val sig = c.buttonState.toLong() shl 32 or c.directTouchButtonState.toLong()
                    if (lastHandSig[h.id] != sig) {
                        lastHandSig[h.id] = sig
                        Log.d("Gato3D", "mano ${h.id}: botones ${Integer.toBinaryString(c.buttonState)} toque ${Integer.toBinaryString(c.directTouchButtonState)}")
                    }
                    p to (curls?.get(i) ?: if (c.buttonState and HAND_CLOSED != 0) 1f else 0f)
                } else null
            }
        }

        private val lastHandSig = HashMap<Long, Long>()
        private val bodyPoses = ArrayList<ControllerPose>()
        private val bodySkeleton = ArrayList<SkeletonJoint>()
        private var bodyOk: Boolean? = null

        private fun joint(j: JointType) =
            bodyPoses.getOrNull(j.ordinal)?.takeIf { it.flags and ControllerPose.ValidBits != 0 }?.pose?.t

        /// Punta del índice de la mano que apunta (índice estirado, medio
        /// doblado), de la derecha primero; null si ninguna. Usa el rastreo de
        /// cuerpo de este cuadro (lo actualiza handCurls).
        private fun pointingTip(): Vector3? {
            if (bodyOk != true) return null
            val hands = listOf(
                Triple(JointType.RIGHT_HAND_PALM, JointType.RIGHT_HAND_INDEX_TIP, JointType.RIGHT_HAND_MIDDLE_TIP),
                Triple(JointType.LEFT_HAND_PALM, JointType.LEFT_HAND_INDEX_TIP, JointType.LEFT_HAND_MIDDLE_TIP),
            )
            for ((palm, index, middle) in hands) {
                val p = joint(palm) ?: continue
                val i = joint(index) ?: continue
                val m = joint(middle) ?: continue
                if (i.distanceTo(p) > POINT_INDEX_M && m.distanceTo(p) < POINT_MIDDLE_M) return i
            }
            return null
        }

        /// Qué tan cerrada está cada mano (izquierda, derecha), por la distancia de
        /// las puntas de los dedos a la palma en el rastreo de cuerpo; null sin datos.
        private fun handCurls(): FloatArray? {
            val ok = scene.updateBodyTrackingBuffersAtTime(bodyPoses, bodySkeleton, 0L)
            if (ok != bodyOk) {
                bodyOk = ok
                Log.d("Gato3D", "rastreo de cuerpo: $ok (${bodyPoses.size} articulaciones)")
            }
            if (!ok) return null
            fun curl(palm: JointType, tips: List<JointType>): Float? {
                val p = bodyPoses.getOrNull(palm.ordinal)?.takeIf { it.flags and ControllerPose.ValidBits != 0 } ?: return null
                val d = tips.mapNotNull { bodyPoses.getOrNull(it.ordinal)?.takeIf { j -> j.flags and ControllerPose.ValidBits != 0 } }
                    .map { it.pose.t.distanceTo(p.pose.t) }
                if (d.isEmpty()) return null
                return ((CURL_OPEN_M - d.average().toFloat()) / (CURL_OPEN_M - CURL_FIST_M)).coerceIn(0f, 1f)
            }
            val l = curl(JointType.LEFT_HAND_PALM, listOf(JointType.LEFT_HAND_INDEX_TIP, JointType.LEFT_HAND_MIDDLE_TIP, JointType.LEFT_HAND_RING_TIP, JointType.LEFT_HAND_LITTLE_TIP))
            val r = curl(JointType.RIGHT_HAND_PALM, listOf(JointType.RIGHT_HAND_INDEX_TIP, JointType.RIGHT_HAND_MIDDLE_TIP, JointType.RIGHT_HAND_RING_TIP, JointType.RIGHT_HAND_LITTLE_TIP))
            if (l == null && r == null) return null
            return floatArrayOf(l ?: 0f, r ?: 0f)
        }

        /// Posición de cada mano rastreada (izquierda, derecha; null si no se ve).
        private fun hands(): List<Vector3?> = frameHands.map { it?.first?.t }

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
            val (left, right) = frameHands
            left?.let { put(11, it.first.t); flags = flags or 1 or (if (it.second >= FIST_CURL) 0x10 else 0) }
            right?.let { put(17, it.first.t); flags = flags or 2 or (if (it.second >= FIST_CURL) 0x20 else 0) }
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
        // slingshot.glb, en unidades del modelo: puntas de la horquilla (las
        // imprime tool/modelos_a_glb.py) y su punto medio.
        val SLING_TIP_L = Vector3(-2.00f, 7.67f, -0.05f)
        val SLING_TIP_R = Vector3(2.00f, 7.85f, 0.05f)
        val SLING_FORK_MID = (SLING_TIP_L + SLING_TIP_R) * 0.5f
        const val SLING_SCALE = 0.018f     // puntas a ~14 cm de la mesa
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
        val BASE_CALIB = Color4(0.10f, 0.55f, 0.20f, 1f)     // calibrando: verde
        val GRID = Color4(0.85f, 0.78f, 0.80f, 1f)
        val IR = Color4(0.20f, 0.75f, 0.85f, 1f)
        val PLACED = Color4(0.30f, 0.75f, 0.40f, 1f)
        val MISSED = Color4(0.85f, 0.25f, 0.25f, 1f)
        val HOLE = Color4(0.02f, 0.02f, 0.02f, 1f)
        val HEAD = Color4(1.0f, 0.62f, 0.18f, 1f)
        val CLAW = Color4(0.75f, 0.75f, 0.80f, 1f)
        val BAND = Color4(0.85f, 0.15f, 0.15f, 1f)
        val BALL = Color4(0.95f, 0.20f, 0.20f, 1f)
        val CONSOLE = Color4(0.15f, 0.15f, 0.18f, 1f)
        val BUTTON = Color4(0.95f, 0.10f, 0.20f, 1f)
        val WHITE = Color4(1f, 1f, 1f, 1f)

        // Personaje gato.
        const val HEAD_SIZE = 0.2f         // m, diámetro de la cabeza
        const val FACES = 4
        const val FACE_NS = 4_000_000_000L // cuánto dura la cara de acierto o fallo
        // Distancia media de las puntas de los dedos a la palma: abierta / puño.
        // shortcut: medidas de una mano adulta típica; ajustar si no cierra completo.
        const val CURL_OPEN_M = 0.085f
        const val CURL_FIST_M = 0.045f
        const val FIST_CURL = 0.7f         // mano "cerrada" en la trama de pose
        // Calibración tocando la cabeza: el índice quieto este tiempo cuenta como toque.
        const val STILL_NS = 800_000_000L
        const val STILL_M = 0.01f
        // shortcut: altura supuesta de la parte de arriba de la cabeza real sobre
        // la mesa; medirla en la máquina (si no, el tablero queda alto o bajo).
        const val HEAD_TOUCH_M = 0.04f
        // Apuntar: punta del índice lejos de la palma y la del medio cerca.
        const val POINT_INDEX_M = 0.075f
        const val POINT_MIDDLE_M = 0.065f
        const val CALIB_WAIT_NS = 2_500_000_000L  // la ESP espera 2 s el botón del otro
        const val BODY_TRACKING = "com.oculus.permission.BODY_TRACKING"
        const val PAW_FRAMES = 4           // igual que CUADROS en tool/pata_a_glb.py
        // La pata (dedos -Z, cojinetes -Y) se voltea a lo largo (dedos a +Z, como
        // la pose de la mano en el visor) y se gira 90° para que los cojinetes
        // queden en la palma. Medido a ojo en el Quest 3.
        val RIGHT_FLIP = Quaternion(0f, 0f, 0f, 1f)
        val PAW_ROT = Quaternion(0.70710677f, 0f, 0f, 0.70710677f) * Quaternion(0f, 0f, 1f, 0f)
        val HAND_CLOSED = ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR or
            ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR
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
        val MARK_A = Color4(0.10f, 0.85f, 0.95f, 1f)
        val MARK_B = Color4(1.0f, 0.85f, 0.10f, 1f)
    }
}
