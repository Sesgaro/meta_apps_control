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
import kotlin.math.sin

/// Tablero 3D del gato en el Quest: una mesa virtual frente al usuario, sobre
/// el passthrough, con la cuadrícula a escala de la máquina, las fichas, la
/// casilla que ve la matriz IR y la cabeza en su posición real.
///
/// En tu turno, pellizca la cabeza y arrástrala sobre el tablero: los motores
/// X/Y la siguen. Solo manos. No conecta nada:
/// propone movimientos al panel 2D (dueño del BLE) por [PanelBus] y dibuja el
/// estado que este le reenvía.
class GameActivity3D : AppSystemActivity() {

    /// Último estado del juego que llegó del panel 2D (hilo principal); lo
    /// lee el sistema de la escena.
    @Volatile private var game: Map<*, *> = emptyMap<Any, Any>()

    private val busListener: (String, Map<*, *>) -> Unit = { method, args ->
        if (method == "game") game = args
    }

    private val scene3d = BoardScene()
    private var status: TextView? = null

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
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(24, 8, 24, 8)
                    text = "Esperando datos…"
                }
            },
            { _ -> UIPanelSettings(QuadShapeOptions(0.36f, 0.08f)) },
            { view, _, _ -> status = view as TextView },
        ),
        button(R.id.game3d_new, "Nuevo juego") { PanelBus.send(this, "newGame") },
        // La escena inmersiva oculta las ventanas 2D: este botón regresa a ellas.
        button(R.id.game3d_back, "Volver a 2D") { backTo2d() },
    )

    private fun button(id: Int, label: String, onClick: () -> Unit) = ViewPanelRegistration(
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
        { _: View, _, _ -> },
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

    // --- Escena -------------------------------------------------------------------

    /// La mesa virtual. Todo cuelga de [root]: su +Z apunta al usuario, +X a su
    /// derecha. El eje X de la máquina va a la derecha y el Y se aleja del
    /// usuario (-Z), como si viera la máquina desde el frente.
    private inner class BoardScene {
        lateinit var root: Entity
        lateinit var head: Entity
        lateinit var target: Entity
        lateinit var base: Entity
        private val tiles = mutableListOf<Entity>()
        private val headParts = mutableListOf<Entity>()
        private val crosses = mutableListOf<List<Entity>>()
        private val rings = mutableListOf<List<Entity>>()
        private var geo: Geometry? = null

        var rootPose = Pose(Vector3(0f, -10f, 0f), Quaternion())
        var placed = false
        var built = false
        var dragging = false

        /// Metros por mm: el tablero mide [BOARD_M] de ancho.
        var k = 0.001f
        var cx = 0f
        var cy = 0f

        private var baseLook = -1
        private var headLook = -1
        private val tileLook = IntArray(9) { -1 }
        private val pieceLook = IntArray(9) { -1 }

        fun local(xMm: Float, yMm: Float, lift: Float = 0f) =
            Vector3((xMm - cx) * k, lift, -(yMm - cy) * k)

        /// Punto en la escena -> mm de la máquina, proyectado sobre la mesa.
        fun toMm(world: Vector3): Pair<Float, Float> {
            val l = rootPose.inverse() * world
            return (cx + l.x / k) to (cy - l.z / k)
        }

        /// Se arma una vez, cuando llega la geometría de la ESP.
        fun build(g: Geometry) {
            geo = g
            k = BOARD_M / (g.right - g.left)
            cx = (g.left + g.right) / 2
            cy = (g.bottom + g.top) / 2
            val w = (g.right - g.left) * k
            val h = (g.top - g.bottom) * k
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
                // Sensor IR al centro de la casilla.
                sphere(root, center + Vector3(0f, 0.002f, 0f), cell * 0.04f, GRID)
                // X: dos barras cruzadas.
                val len = cell * 0.36f
                val thick = cell * 0.06f
                crosses += listOf(45f, -45f).map { a ->
                    Entity.create(
                        listOf(
                            Mesh(Uri.parse("mesh://box")),
                            Box(Vector3(-len, 0f, -thick), Vector3(len, cell * 0.08f, thick)),
                            material(PIECE_X),
                            Transform(Pose(center + Vector3(0f, 0.004f, 0f), yaw(a))),
                            TransformParent(root),
                            Visible(false),
                        ),
                    )
                }
                // O: anillo de esferas.
                rings += (0 until 14).map { i ->
                    val t = i * 2 * Math.PI.toFloat() / 14
                    sphere(root, center + Vector3(cos(t) * cell * 0.3f, 0.02f, sin(t) * cell * 0.3f), cell * 0.055f, PIECE_O, Visible(false))
                }
            }
            // Cabeza: esfera naranja con una varilla, y la referencia en blanco.
            head = Entity.create(listOf(Transform(Pose(local(cx, cy, 0.03f), Quaternion())), TransformParent(root)))
            headParts += sphere(head, Vector3(0f, 0f, 0f), 0.018f, HEAD)
            headParts += box(head, Vector3(-0.003f, 0f, -0.003f), Vector3(0.003f, 0.08f, 0.003f), HEAD)
            target = sphere(root, local(cx, cy, 0.004f), 0.008f, WHITE)

            Entity.createPanelEntity(
                R.id.game3d_status,
                Transform(Pose(Vector3(0f, 0.17f, -h / 2 - 0.04f), Quaternion())),
                TransformParent(root),
            )
            Entity.createPanelEntity(
                R.id.game3d_new,
                Transform(Pose(Vector3(-0.08f, 0.09f, -h / 2 - 0.04f), Quaternion())),
                TransformParent(root),
            )
            Entity.createPanelEntity(
                R.id.game3d_back,
                Transform(Pose(Vector3(0.08f, 0.09f, -h / 2 - 0.04f), Quaternion())),
                TransformParent(root),
            )
            built = true
        }

        fun place(pose: Pose) {
            rootPose = pose
            if (built) root.setComponent(Transform(pose))
            placed = true
        }

        fun headWorld(): Vector3 = rootPose * local(num("posX"), num("posY"), 0.03f)

        fun update(hover: Boolean) {
            if (geo == null) return
            head.setComponent(Transform(Pose(local(num("posX"), num("posY"), 0.03f), Quaternion())))
            target.setComponent(Transform(Pose(local(num("refX"), num("refY"), 0.004f), Quaternion())))

            val mine = canMove()
            val bl = if (mine) 1 else 0
            if (bl != baseLook) {
                baseLook = bl
                base.setComponent(material(if (mine) BASE_MINE else BASE_LOCKED))
            }
            val hl = if (dragging) 2 else if (hover && mine) 1 else 0
            if (hl != headLook) {
                headLook = hl
                val c = if (hl == 2) HEAD_GRAB else if (hl == 1) HEAD_HOVER else HEAD
                headParts.forEach { it.setComponent(material(c)) }
            }

            val board = game["board"] as? List<*>
            val ir = int("irCell", -1)
            val last = int("lastCell", -1)
            val lastResult = int("lastResult")
            for (c in 0 until 9) {
                // Piezas: 0 vacía, 1 X, 2 O.
                val p = (board?.getOrNull(c) as? Number)?.toInt() ?: 0
                if (p != pieceLook[c]) {
                    pieceLook[c] = p
                    crosses[c].forEach { it.setComponent(Visible(p == 1)) }
                    rings[c].forEach { it.setComponent(Visible(p == 2)) }
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

    /// Cada cuadro: coloca la mesa frente al usuario, arma el tablero cuando
    /// llega la geometría, sigue el arrastre de la cabeza y actualiza todo.
    private inner class GameSystem : SystemBase() {
        private var hand: Entity? = null
        private var lastSentAt = 0L
        private var statusText = ""

        override fun execute() {
            val body = Query.where { has(AvatarBody.id) }.eval()
                .filter { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
                .firstOrNull()?.getComponent<AvatarBody>() ?: return

            // Antes de rastrear el visor reporta la cabeza en el piso: se espera.
            val viewer = scene.getViewerPose()
            if (viewer.t.y < MIN_HEAD_HEIGHT) return
            if (!scene3d.placed || viewer.t.distanceTo(scene3d.rootPose.t) > 2f) placeInFront(viewer)

            val g = geometry()
            if (g != null && !scene3d.built) scene3d.build(g)
            updateStatus()
            if (!scene3d.built) return

            val headPos = scene3d.headWorld()
            val hands = listOf(body.rightHand to PINCH_R, body.leftHand to PINCH_L)
            var hover = false

            if (hand == null) {
                for ((h, bits) in hands) {
                    val c = h.tryGetComponent<Controller>() ?: continue
                    if (!c.isActive || c.type != ControllerType.HAND) continue
                    val pose = h.tryGetComponent<Transform>()?.transform ?: continue
                    val near = pose.t.distanceTo(headPos) < GRAB_RADIUS
                    hover = hover || near
                    if (near && canMove() && (c.buttonState and c.changedButtons and bits) != 0) {
                        hand = h
                        scene3d.dragging = true
                        break
                    }
                }
            } else {
                val h = hand!!
                val c = h.tryGetComponent<Controller>()
                val pose = h.tryGetComponent<Transform>()?.transform
                val bits = if (h == body.rightHand) PINCH_R else PINCH_L
                if (c == null || pose == null || !c.isActive || (c.buttonState and bits) == 0 || !canMove()) {
                    hand = null
                    scene3d.dragging = false
                } else {
                    drag(pose.t, g)
                }
            }
            scene3d.update(hover)
        }

        private fun drag(handPos: Vector3, g: Geometry?) {
            if (g == null) return
            val now = System.currentTimeMillis()
            if (now - lastSentAt < SEND_EVERY_MS) return
            lastSentAt = now
            val (x, y) = scene3d.toMm(handPos)
            PanelBus.send(
                this@GameActivity3D,
                "move",
                mapOf("x" to x.coerceIn(g.xMin, g.xMax).toDouble(), "y" to y.coerceIn(g.yMin, g.yMax).toDouble()),
            )
        }

        private fun placeInFront(viewer: Pose) {
            // A 45 cm al frente, a la altura de una mesa (45 cm bajo los ojos).
            val f = viewer.forward()
            var flat = Vector3(f.x, 0f, f.z)
            if (flat.length() < 1e-3f) flat = Vector3(0f, 0f, -1f)
            flat = flat.normalize()
            val pos = viewer.t + flat * 0.5f + Vector3(0f, -0.45f, 0f)
            val toViewer = -flat
            val a = atan2(toViewer.x, toViewer.z)
            scene3d.place(Pose(pos, Quaternion(cos(a / 2), 0f, sin(a / 2), 0f)))
        }

        private fun updateStatus() {
            val text = (game["status"] as? String) ?: "Esperando datos del panel 2D…"
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
        const val GRAB_RADIUS = 0.08f      // m desde la cabeza
        const val SEND_EVERY_MS = 50L      // 20 movimientos/s, como la UART
        const val MIN_HEAD_HEIGHT = 0.3f

        // Pellizco de índice: ButtonA (derecha) / ButtonX (izquierda); con la
        // mano cerrada llega como squeeze.
        val PINCH_R = ButtonBits.ButtonA or ButtonBits.ButtonSqueezeR
        val PINCH_L = ButtonBits.ButtonX or ButtonBits.ButtonSqueezeL

        val BASE_LOCKED = Color4(0.20f, 0.17f, 0.19f, 1f)
        val BASE_MINE = Color4(0.42f, 0.10f, 0.24f, 1f)      // tu turno: rosa oscuro
        val GRID = Color4(0.85f, 0.78f, 0.80f, 1f)
        val IR = Color4(0.20f, 0.75f, 0.85f, 1f)
        val PLACED = Color4(0.30f, 0.75f, 0.40f, 1f)
        val MISSED = Color4(0.85f, 0.25f, 0.25f, 1f)
        val PIECE_X = Color4(0.74f, 0.0f, 0.29f, 1f)        // rosa del tema
        val PIECE_O = Color4(0.95f, 0.85f, 0.90f, 1f)
        val HEAD = Color4(1.0f, 0.62f, 0.18f, 1f)
        val HEAD_HOVER = Color4(1.0f, 0.78f, 0.45f, 1f)
        val HEAD_GRAB = Color4(1.0f, 0.92f, 0.70f, 1f)
        val WHITE = Color4(1f, 1f, 1f, 1f)

        /// Rotación de [deg] grados alrededor del eje Y.
        fun yaw(deg: Float): Quaternion {
            val h = Math.toRadians(deg.toDouble()).toFloat() / 2
            return Quaternion(cos(h), 0f, sin(h), 0f)
        }
    }
}
