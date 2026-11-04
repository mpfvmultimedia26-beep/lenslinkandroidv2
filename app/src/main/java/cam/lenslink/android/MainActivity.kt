package cam.lenslink.android

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ---------------------------------------------------------------- paleta (oscuro + ámbar)
private val INK = Color.parseColor("#1C1C1E")   // texto e iconos principales (gris casi negro)
private val INK2 = Color.parseColor("#5C5C61")  // texto secundario
private val RED = Color.parseColor("#FF3B30")

/** SurfaceView que mantiene 16:9 dentro del espacio disponible (letterbox). */
private class AspectSurfaceView(ctx: Context) : SurfaceView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        val maxW = MeasureSpec.getSize(w)
        val maxH = MeasureSpec.getSize(h)
        var width = maxW
        var height = width * 9 / 16
        if (height > maxH) { height = maxH; width = height * 16 / 9 }
        setMeasuredDimension(width, height)
    }
}

/** Icono dibujado a mano (sin recursos): rayo, voltear, puntos, look. */
private class IconView(ctx: Context, private val kind: Int) : View(ctx) {
    companion object { const val BOLT = 0; const val FLIP = 1; const val DOTS = 2; const val LOOK = 3 }

    var active = false
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun arrow(c: Canvas, cx: Float, cy: Float, r: Float, deg: Float, k: Float) {
        val t = Math.toRadians(deg.toDouble())
        val px = cx + r * cos(t).toFloat(); val py = cy + r * sin(t).toFloat()
        val dx = -sin(t).toFloat(); val dy = cos(t).toFloat()
        val nx = cos(t).toFloat(); val ny = sin(t).toFloat()
        c.drawLine(px, py, px - dx * k + nx * k * 0.8f, py - dy * k + ny * k * 0.8f, p)
        c.drawLine(px, py, px - dx * k - nx * k * 0.8f, py - dy * k - ny * k * 0.8f, p)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val s = minOf(width, height) * 0.30f
        p.color = INK
        p.style = Paint.Style.STROKE
        when (kind) {
            BOLT -> {
                val path = Path()
                path.moveTo(cx + 0.2f * s, cy - s)
                path.lineTo(cx - 0.6f * s, cy + 0.15f * s)
                path.lineTo(cx, cy + 0.15f * s)
                path.lineTo(cx - 0.2f * s, cy + s)
                path.lineTo(cx + 0.6f * s, cy - 0.15f * s)
                path.lineTo(cx, cy - 0.15f * s)
                path.close()
                c.drawPath(path, p)
            }
            FLIP -> {
                val r = s * 0.95f
                val box = RectF(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(box, 200f, 130f, false, p); arrow(c, cx, cy, r, 330f, s * 0.45f)
                c.drawArc(box, 20f, 130f, false, p); arrow(c, cx, cy, r, 150f, s * 0.45f)
            }
            DOTS -> {
                p.style = Paint.Style.FILL
                for (i in -1..1) c.drawCircle(cx + i * s * 0.75f, cy, s * 0.17f, p)
            }
            LOOK -> {
                c.drawCircle(cx - s * 0.35f, cy, s * 0.7f, p)
                c.drawCircle(cx + s * 0.35f, cy, s * 0.7f, p)
            }
        }
    }
}

/** Marco de enfoque tipo iPhone. */
private class FocusRing(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2f * d
    }
    private var cx = 0f
    private var cy = 0f
    private var k = 1f
    var active = false
        private set
    private val hide = Runnable {
        animate().alpha(0f).setDuration(250).withEndAction { active = false }.start()
    }

    init { alpha = 0f; isClickable = false; isFocusable = false }

    fun show(x: Float, y: Float) {
        removeCallbacks(hide)
        animate().cancel()
        cx = x; cy = y; active = true; alpha = 1f
        ValueAnimator.ofFloat(1.5f, 1f).apply {
            duration = 220
            addUpdateListener { k = it.animatedValue as Float; invalidate() }
        }.start()
        postDelayed(hide, 2500)
    }

    override fun onDraw(c: Canvas) {
        if (!active) return
        val r = 34f * d * k
        c.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), 6f * d, 6f * d, p)
        val t = 5f * d
        c.drawLine(cx - r, cy, cx - r + t, cy, p); c.drawLine(cx + r, cy, cx + r - t, cy, p)
        c.drawLine(cx, cy - r, cx, cy - r + t, p); c.drawLine(cx, cy + r, cx, cy + r - t, p)
    }
}

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("lenslink", Context.MODE_PRIVATE) }

    private lateinit var root: FrameLayout
    private lateinit var preview: AspectSurfaceView
    private lateinit var touch: View
    private lateinit var ring: FocusRing
    private lateinit var tally: View
    private lateinit var statusPill: TextView
    private lateinit var batteryText: TextView
    private lateinit var hud: TextView
    private lateinit var lensCol: LinearLayout
    private lateinit var flashBtn: IconView
    private lateinit var lookPill: TextView
    private lateinit var shutterDot: View
    private lateinit var settings: ScrollView
    private lateinit var serverBtn: TextView
    private lateinit var ipText: TextView
    private lateinit var capsText: TextView
    private lateinit var dimLayer: FrameLayout
    private lateinit var dimBattery: TextView

    private val lensChips = ArrayList<TextView>()
    private var lensKey = ""
    private val segSetters = HashMap<String, (String) -> Unit>()
    private val segViews = HashMap<String, List<Pair<String, TextView>>>()

    private var dimmed = false
    private var surfaceOk = false
    private var previewAttached = false
    private var serverStoppedByUser = false
    private var pendingStart = false
    private var aeLocked = false
    private var lastError: String? = null

    // ------------------------------------------------------------------ helpers de estilo

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, sp: Float, color: Int = INK, face: String = "sans-serif-medium",
                      caps: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        typeface = Typeface.create(face, Typeface.NORMAL)
        includeFontPadding = false
        if (caps) { isAllCaps = true; letterSpacing = 0.12f }
    }

    private fun pill(color: Int, radiusDp: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    /** Fondo tipo cristal: blanco translúcido con degradado suave y borde claro. */
    private fun glass(radiusDp: Int, strong: Boolean = false) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor(if (strong) "#F5FFFFFF" else "#B8FFFFFF"),
                   Color.parseColor(if (strong) "#E0F2F2F7" else "#8CFFFFFF"))
    ).apply {
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), Color.parseColor("#99FFFFFF"))
    }

    private fun lp(w: Int, h: Int, g: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, g)

    // ------------------------------------------------------------------ construcción de la UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val a = window.attributes
            a.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = a
        }
        buildUi()
        setContentView(root)
        requestPerms()
    }

    @Suppress("DEPRECATION")
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // 1) vista previa
        preview = AspectSurfaceView(this)
        preview.holder.setFixedSize(1280, 720)
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { surfaceOk = true; attachPreview() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                surfaceOk = false; previewAttached = false
                StreamService.instance?.engine?.setPreview(null, wait = true)
            }
        })
        root.addView(preview, lp(-1, -1, Gravity.CENTER))

        // 2) gestos: pellizco = zoom, toque = enfoque, arrastre vertical tras enfocar = exposición
        touch = View(this)
        setupGestures()
        root.addView(touch, lp(-1, -1))
        ring = FocusRing(this)
        root.addView(ring, lp(-1, -1))

        tally = View(this).apply {
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(3), RED) }
            visibility = View.GONE
            isClickable = false
        }
        root.addView(tally, lp(-1, -1))

        // 3) controles
        val controls = FrameLayout(this)
        root.addView(controls, lp(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            controls.setPadding(max(insets.systemWindowInsetLeft, dp(16)), dp(10),
                max(insets.systemWindowInsetRight, dp(16)), dp(10))
            insets
        }

        statusPill = label("", 12f).apply {
            background = glass(20)
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        controls.addView(statusPill, lp(-2, -2, Gravity.TOP or Gravity.START))

        val topRight = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        batteryText = label("", 12f, INK2).apply { background = glass(14); setPadding(dp(10), dp(6), dp(10), dp(6)) }
        topRight.addView(batteryText, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        val dots = IconView(this, IconView.DOTS).apply {
            background = glass(20)
            setOnClickListener { toggleSettings() }
        }
        topRight.addView(dots, LinearLayout.LayoutParams(dp(40), dp(40)))
        controls.addView(topRight, lp(-2, -2, Gravity.TOP or Gravity.END))

        // columna derecha: lentes, linterna, voltear
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        lensCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        right.addView(lensCol)
        flashBtn = IconView(this, IconView.BOLT).apply {
            background = glass(20)
            setOnClickListener {
                val e = StreamService.instance?.engine ?: return@setOnClickListener
                if (!e.hasTorch()) { toast("Esta cámara no tiene linterna"); return@setOnClickListener }
                e.setTorch(!e.torchOn()); active = e.torchOn()
                background = if (e.torchOn()) glass(20, true) else glass(20)
            }
        }
        right.addView(flashBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { topMargin = dp(10) })
        val flip = IconView(this, IconView.FLIP).apply {
            background = glass(20)
            setOnClickListener { StreamService.instance?.engine?.flip(); flashBtn.active = false }
        }
        right.addView(flip, LinearLayout.LayoutParams(dp(40), dp(40)).apply { topMargin = dp(10) })
        controls.addView(right, lp(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))

        // abajo: look (centro), obturador (derecha), lectura (izquierda)
        lookPill = label("NATURAL", 12f, INK, caps = true).apply {
            background = glass(20)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener { cycleLook() }
        }
        controls.addView(lookPill, lp(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(6) })

        hud = label("", 13f, INK).apply { alpha = 0f; background = glass(14); setPadding(dp(12), dp(6), dp(12), dp(6)) }
        controls.addView(hud, lp(-2, -2, Gravity.BOTTOM or Gravity.START).apply { bottomMargin = dp(16) })

        val shutter = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(3), Color.WHITE) }
            setOnClickListener { toggleStream() }
        }
        shutterDot = View(this)
        shutter.addView(shutterDot, lp(dp(46), dp(46), Gravity.CENTER))
        controls.addView(shutter, lp(dp(66), dp(66), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(2) })

        // 4) panel de ajustes
        settings = buildSettings()
        settings.visibility = View.GONE
        controls.addView(settings, lp(dp(290), -1, Gravity.END).apply {
            marginEnd = dp(62); topMargin = dp(48); bottomMargin = dp(8)
        })

        // 5) modo ahorro: negro + solo batería
        dimLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_DOWN) wake(); true }
        }
        dimBattery = label("", 60f, Color.parseColor("#55FFFFFF"), "sans-serif-thin")
        dimLayer.addView(dimBattery, lp(-2, -2, Gravity.CENTER))
        root.addView(dimLayer, lp(-1, -1))
    }

    private fun buildSettings(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        col.addView(label("Ajustes", 16f, INK, "sans-serif-light"))
        fun add(v: View) = col.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        add(segmented("res", "Resolución", listOf("720p" to "720p", "1080p" to "1080p", "4K" to "4K")) { v ->
            prefs.edit().putString("res", v).apply()
            applyFormat(v, null, null)
        })
        add(segmented("fps", "Fotogramas", listOf("30" to "30", "60" to "60")) { v ->
            prefs.edit().putInt("fps", v.toInt()).apply()
            applyFormat(null, v.toInt(), null)
        })
        add(segmented("codec", "Códec", listOf("hevc" to "HEVC", "h264" to "H.264")) { v ->
            prefs.edit().putString("codec", v).apply()
            applyFormat(null, null, v)
        })
        add(segmented("quality", "Calidad", listOf("balanced" to "Equilibrada", "maximum" to "Máxima")) { v ->
            prefs.edit().putString("quality", v).apply()
            StreamService.instance?.engine?.changeQuality(v)
        })
        add(segmented("armed", "Inicio remoto desde OBS", listOf("off" to "Apagado", "on" to "Activo")) { v ->
            prefs.edit().putBoolean("armed", v == "on").apply()
            StreamService.instance?.changeArmed(v == "on")
        })

        serverBtn = label("", 13f, INK).apply {
            gravity = Gravity.CENTER
            background = pill(Color.parseColor("#14000000"), 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { toggleServer() }
        }
        add(serverBtn)
        ipText = label("", 11f, INK2, "sans-serif")
        add(ipText)
        add(label("Admite esta cámara", 10f, INK2, caps = true))
        capsText = label("", 11f, INK, "sans-serif")
        col.addView(capsText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        return ScrollView(this).apply {
            background = glass(18, true)
            isVerticalScrollBarEnabled = false
            addView(col)
        }
    }

    private fun segmented(key: String, title: String, options: List<Pair<String, String>>,
                          onPick: (String) -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, 10f, INK2, caps = true))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = pill(Color.parseColor("#14000000"), 14) }
        val views = options.map { (value, text) ->
            label(text, 12f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnClickListener { onPick(value); refresh() }
            }.also { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        segViews[key] = options.map { it.first }.zip(views)
        segSetters[key] = { sel ->
            options.forEachIndexed { i, o ->
                val on = o.first == sel
                views[i].setTextColor(if (on) INK else INK2)
                views[i].background = if (on) pill(Color.WHITE, 12) else null
            }
        }
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        return col
    }

    // ------------------------------------------------------------------ gestos

    @Suppress("DEPRECATION")
    private fun rotationDeg(): Int = windowManager.defaultDisplay.rotation * 90

    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val e = StreamService.instance?.engine ?: return true
                e.zoomTo(e.zoomNow() * d.scaleFactor)
                showHud(String.format(Locale.US, "%.1f×", e.zoomNow()))
                updateLensChips()
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                settings.visibility = View.GONE
                val eng = StreamService.instance?.engine ?: return true
                val px = (e.x - preview.left) / preview.width.toFloat()
                val py = (e.y - preview.top) / preview.height.toFloat()
                if (px < 0f || px > 1f || py < 0f || py > 1f) return true
                aeLocked = false
                eng.focusAt(px, py, rotationDeg())
                ring.show(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val eng = StreamService.instance?.engine ?: return
                aeLocked = !aeLocked
                eng.lockAeAwb(aeLocked)
                ring.show(e.x, e.y)
                showHud(if (aeLocked) "AE/AWB bloqueado" else "AE/AWB libre")
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scale.isInProgress || !ring.active || abs(dy) <= abs(dx)) return false
                val eng = StreamService.instance?.engine ?: return false
                eng.biasTo(eng.biasNow() + dy / max(touch.height, 1) * 4f)
                showHud(String.format(Locale.US, "%+.1f EV", eng.biasNow()))
                return true
            }
        })
        touch.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) tap.onTouchEvent(ev)
            true
        }
    }

    private val hudHide = Runnable { hud.animate().alpha(0f).setDuration(300).start() }

    private fun showHud(text: String) {
        hud.text = text
        hud.animate().cancel()
        hud.alpha = 1f
        ui.removeCallbacks(hudHide)
        ui.postDelayed(hudHide, 1200)
    }

    private fun applyFormat(res: String?, fps: Int?, codec: String?) {
        val e = StreamService.instance?.engine ?: return
        when (e.setFormat(res, fps, codec)) {
            0 -> toast(if (fps != null) "${e.resolution} a $fps fps no está disponible en este móvil"
                       else "Ese formato no lo soporta esta cámara")
            2 -> toast("${e.resolution}: en este móvil el máximo es 30 fps")
        }
        prefs.edit().putString("res", e.resolution).putInt("fps", e.fps).putString("codec", e.codec).apply()
    }

    private fun dimOptions(key: String, available: Set<String>) {
        segViews[key]?.forEach { (v, view) -> view.alpha = if (v in available) 1f else 0.35f }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ acciones

    private fun toggleSettings() {
        settings.visibility = if (settings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun cycleLook() {
        val e = StreamService.instance?.engine ?: return
        val ls = e.looks()
        val i = ls.indexOfFirst { it.first == e.currentLook }
        val next = ls[(i + 1) % ls.size]
        e.setLook(next.first)
        prefs.edit().putString("look", next.first).apply()
        lookPill.text = next.second
        showHud("Look · ${next.second}")
    }

    private fun toggleStream() {
        val s = StreamService.instance
        if (s == null) {
            if (!hasCamPerm()) { requestPerms(); return }
            serverStoppedByUser = false
            pendingStart = true
            startServer()
            return
        }
        if (s.engine.running) { s.stopCamera(disarm = true); segSetters["armed"]?.invoke("off") }
        else s.startCamera()
    }

    private fun toggleServer() {
        if (StreamService.instance != null) {
            serverStoppedByUser = true
            startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
        } else {
            serverStoppedByUser = false
            if (hasCamPerm()) startServer() else requestPerms()
        }
    }

    // ------------------------------------------------------------------ permisos y servicio

    private fun hasCamPerm() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val need = mutableListOf<String>()
        if (!hasCamPerm()) need += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (hasCamPerm()) startServer() else toast("Hace falta el permiso de cámara")
    }

    private fun startServer() = startForegroundService(Intent(this, StreamService::class.java))

    // ------------------------------------------------------------------ vista previa

    private fun attachPreview() {
        val s = StreamService.instance ?: return
        if (surfaceOk && !dimmed && !previewAttached) {
            s.engine.setPreview(preview.holder.surface)
            previewAttached = true
        }
    }

    // ------------------------------------------------------------------ modo ahorro (5 s sin tocar)

    private val idle = Runnable { dim() }
    private val dimTick = object : Runnable {
        override fun run() {
            // Cambia de sitio cada 30 s para no marcar la pantalla OLED y refresca la batería.
            dimBattery.text = batteryLabel()
            dimBattery.translationX = ((Math.random() - 0.5) * dp(160)).toFloat()
            dimBattery.translationY = ((Math.random() - 0.5) * dp(120)).toFloat()
            ui.postDelayed(this, 30_000)
        }
    }

    private fun scheduleIdle() { ui.removeCallbacks(idle); ui.postDelayed(idle, 180_000) }

    override fun onUserInteraction() { if (!dimmed) scheduleIdle() }

    private fun setBrightness(v: Float) {
        val a = window.attributes
        a.screenBrightness = v
        window.attributes = a
    }

    private fun dim() {
        if (dimmed) return
        dimmed = true
        settings.visibility = View.GONE
        dimLayer.visibility = View.VISIBLE
        setBrightness(0.01f)
        ui.removeCallbacks(tick)
        // Sin emitir no hace falta cámara abierta: se suelta la vista previa (la cámara se apaga).
        val s = StreamService.instance
        if (s != null && !s.engine.running) { s.engine.setPreview(null); previewAttached = false }
        ui.post(dimTick)
    }

    private fun wake() {
        if (!dimmed) return
        dimmed = false
        dimLayer.visibility = View.GONE
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        ui.removeCallbacks(dimTick)
        dimBattery.translationX = 0f; dimBattery.translationY = 0f
        ui.post(tick)
        scheduleIdle()
    }

    private fun batteryPct(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0) -1 else level * 100 / max(scale, 1)
    }

    private fun batteryLabel(): String = batteryPct().let { if (it < 0) "--" else "$it%" }

    // ------------------------------------------------------------------ refresco periódico

    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    private fun localIps(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .joinToString(", ") { it.hostAddress ?: "" }
            .ifEmpty { "sin red" }
    } catch (e: Exception) { "?" }

    private fun fmtZ(z: Float): String =
        if (abs(z - Math.round(z)) < 0.05f) Math.round(z).toString()
        else String.format(Locale.US, "%.1f", z).removePrefix("0")

    private fun updateLensChips() {
        val e = StreamService.instance?.engine
        val lenses = e?.lensList() ?: emptyList()
        val key = lenses.joinToString("|") { it.first }
        if (key != lensKey) {
            lensKey = key
            lensCol.removeAllViews(); lensChips.clear()
            if (lenses.size > 1) lenses.forEach { (_, factor) ->
                val chip = label("", 12f).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        StreamService.instance?.engine?.zoomTo(factor)
                        showHud(fmtZ(factor) + "×"); updateLensChips()
                    }
                }
                lensChips += chip
                lensCol.addView(chip, LinearLayout.LayoutParams(dp(40), dp(40)).apply { topMargin = dp(6) })
            }
        }
        if (lensChips.size != lenses.size) return
        val z = e?.zoomNow() ?: 1f
        val sel = lenses.indexOfLast { it.second <= z + 0.05f }.coerceAtLeast(0)
        lenses.forEachIndexed { i, (_, factor) ->
            val on = i == sel
            val c = lensChips[i]
            c.text = if (on) fmtZ(z) + "×" else fmtZ(factor)
            c.setTextColor(if (on) INK else INK2)
            c.background = if (on) glass(20, true) else glass(20)
        }
    }

    private fun refresh() {
        val s = StreamService.instance
        if (s == null) {
            previewAttached = false
            if (!serverStoppedByUser && hasCamPerm()) startServer()
        } else if (pendingStart) {
            pendingStart = false
            s.startCamera()
        }
        attachPreview()

        val e = s?.engine
        batteryText.text = batteryLabel()

        // estado
        val dot = if (e?.running == true) "●" else "○"
        val txt = when {
            s == null -> "Servidor apagado"
            e!!.running -> "$dot  EN VIVO · ${e.resolution} · ${e.fps} · ${e.codec.uppercase()}" +
                (if (s.paused) " · pausa" else "") + (if (s.clientConnected) "" else " · sin OBS")
            s.clientConnected -> "$dot  OBS conectado"
            else -> "$dot  Esperando OBS"
        }
        val sb = SpannableStringBuilder(txt)
        if (e?.running == true) sb.setSpan(ForegroundColorSpan(RED), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        statusPill.text = sb
        tally.visibility = if (s?.tallyProgram == true) View.VISIBLE else View.GONE

        // obturador
        shutterDot.background = GradientDrawable().apply {
            if (e?.running == true) { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat() }
            else shape = GradientDrawable.OVAL
            setColor(if (e?.running == true) RED else Color.WHITE)
        }

        // look y lentes
        val look = e?.looks()?.firstOrNull { it.first == e.currentLook }
        lookPill.text = look?.second ?: "Natural"
        val torch = e?.torchOn() == true
        flashBtn.active = torch
        flashBtn.background = if (torch) glass(20, true) else glass(20)
        updateLensChips()

        // ajustes sincronizados con el estado real (OBS también puede cambiarlos)
        segSetters["res"]?.invoke(e?.resolution ?: prefs.getString("res", "1080p") ?: "1080p")
        segSetters["fps"]?.invoke((e?.fps ?: prefs.getInt("fps", 60)).toString())
        segSetters["codec"]?.invoke(e?.codec ?: prefs.getString("codec", "hevc") ?: "hevc")
        segSetters["quality"]?.invoke(e?.quality ?: prefs.getString("quality", "balanced") ?: "balanced")
        segSetters["armed"]?.invoke(if (s?.armed ?: prefs.getBoolean("armed", false)) "on" else "off")
        if (e != null) {
            val mx = e.formatMatrix()
            dimOptions("res", mx.filter { it.value.isNotEmpty() }.keys)
            dimOptions("fps", (mx[e.resolution] ?: emptyList()).map { it.toString() }.toSet())
            capsText.text = mx.entries.joinToString("\n") {
                "${it.key}   ${it.value.joinToString(" · ").ifEmpty { "no disponible" }} fps"
            }
        }
        serverBtn.text = if (s == null) "Encender servidor" else "Apagar servidor"
        ipText.text = "${localIps()} : ${Proto.PORT}" +
            (if (s?.obsHost?.isNotEmpty() == true) "\nOBS: ${s.obsHost}" else "")

        // avisos
        val err = e?.lastError ?: s?.lastError
        if (err != null && err != lastError) toast(err)
        lastError = err
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onResume() {
        super.onResume()
        immersive()
        if (dimmed) wake() else { ui.post(tick); scheduleIdle() }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick); ui.removeCallbacks(idle); ui.removeCallbacks(dimTick)
        dimmed = false
        dimLayer.visibility = View.GONE
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }
}
