package cam.lenslink.android

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ---------------------------------------------------------------- paleta (oscuro + ámbar)
private val INK = Color.parseColor("#1C1C1E")   // texto e iconos principales (gris casi negro)
private val INK2 = Color.parseColor("#5C5C61")  // texto secundario
private val RED = Color.parseColor("#FF3B30")
private val ORANGE = Color.parseColor("#FF9F0A")

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
    companion object { const val BOLT = 0; const val FLIP = 1; const val DOTS = 2; const val LOOK = 3; const val SLIDERS = 4; const val BATTERY = 5 }

    var active = false
        set(v) { field = v; invalidate() }
    var tint: Int? = null
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
        p.color = tint ?: INK
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
            SLIDERS -> {
                val ys = floatArrayOf(-0.8f, 0f, 0.8f)
                val xs = floatArrayOf(-0.35f, 0.4f, -0.1f)
                for (i in 0..2) {
                    p.style = Paint.Style.STROKE
                    c.drawLine(cx - s, cy + ys[i] * s, cx + s, cy + ys[i] * s, p)
                    p.style = Paint.Style.FILL
                    c.drawCircle(cx + xs[i] * s, cy + ys[i] * s, s * 0.22f, p)
                }
            }
            BATTERY -> {
                val w = s * 1.5f; val h = s * 0.8f
                c.drawRoundRect(RectF(cx - w, cy - h, cx + w * 0.8f, cy + h), s * 0.25f, s * 0.25f, p)
                c.drawLine(cx + w * 0.95f, cy - h * 0.35f, cx + w * 0.95f, cy + h * 0.35f, p)
                p.style = Paint.Style.FILL
                c.drawRoundRect(RectF(cx - w + s * 0.3f, cy - h + s * 0.3f, cx - w + s * 1.2f, cy + h - s * 0.3f),
                    s * 0.1f, s * 0.1f, p)
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

/** Cuadrícula y nivel de horizonte, con el mismo recuadro 16:9 que la vista previa. */
private class OverlayView(ctx: Context) : View(ctx) {
    var grid = 0            // 0 apagada, 1 tercios, 2 cruz central
        set(v) { field = v; invalidate() }
    var levelOn = false
        set(v) { field = v; invalidate() }
    private var angle = 0f
    private var flat = false
    private val d = resources.displayMetrics.density
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFFFFF"); strokeWidth = 1.2f * d; style = Paint.Style.STROKE
    }
    private val strong = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 2.6f * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40000000"); strokeWidth = 2.6f * d; style = Paint.Style.STROKE
    }

    override fun onMeasure(w: Int, h: Int) {
        val maxW = MeasureSpec.getSize(w)
        val maxH = MeasureSpec.getSize(h)
        var width = maxW
        var height = width * 9 / 16
        if (height > maxH) { height = maxH; width = height * 16 / 9 }
        setMeasuredDimension(width, height)
    }

    /** deg = inclinación del horizonte respecto a la pantalla (con suavizado). */
    fun setLevel(deg: Float, isFlat: Boolean) {
        angle += (deg - angle) * 0.3f
        flat = isFlat
        invalidate()
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        c.drawLine(x1, y1, x2, y2, shade)
        c.drawLine(x1, y1, x2, y2, soft)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        when (grid) {
            1 -> for (i in 1..2) {
                line(c, w * i / 3f, 0f, w * i / 3f, h)
                line(c, 0f, h * i / 3f, w, h * i / 3f)
            }
            2 -> {
                val s = 16f * d
                line(c, w / 2 - s, h / 2, w / 2 + s, h / 2)
                line(c, w / 2, h / 2 - s, w / 2, h / 2 + s)
            }
        }
        if (levelOn && !flat) {
            val cx = w / 2; val cy = h / 2; val half = w * 0.13f
            line(c, cx - half - 22f * d, cy, cx - half - 8f * d, cy)
            line(c, cx + half + 8f * d, cy, cx + half + 22f * d, cy)
            c.save()
            c.rotate(angle, cx, cy)
            if (abs(angle) < 1f) {
                c.drawLine(cx - half, cy, cx + half, cy, shade)
                c.drawLine(cx - half, cy, cx + half, cy, strong)   // a nivel: línea gruesa y sólida
            } else line(c, cx - half, cy, cx + half, cy)
            c.restore()
        }
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
    private lateinit var batteryBadge: IconView
    private var dimSecs = 0
    private lateinit var hud: TextView
    private lateinit var lensCol: LinearLayout
    private lateinit var flashBtn: IconView
    private lateinit var lookPill: TextView
    private lateinit var shutterDot: View
    private lateinit var settings: ScrollView
    private lateinit var serverBtn: TextView
    private lateinit var ipText: TextView
    private lateinit var capsText: TextView
    private lateinit var overlay: OverlayView
    private lateinit var manual: LinearLayout
    private val mBars = HashMap<String, SeekBar>()
    private val mValues = HashMap<String, TextView>()
    private val mChips = HashMap<String, TextView>()
    private var updatingBars = false
    private var resumed = false
    private var levelRegistered = false
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

        overlay = OverlayView(this)
        overlay.grid = prefs.getInt("grid", 0)
        overlay.levelOn = prefs.getBoolean("level", false)
        root.addView(overlay, lp(-1, -1, Gravity.CENTER))

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
        batteryBadge = IconView(this, IconView.BATTERY).apply { visibility = View.GONE }
        topRight.addView(batteryBadge, LinearLayout.LayoutParams(dp(28), dp(24)).apply { marginEnd = dp(4) })
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

        // izquierda: abrir/cerrar controles manuales
        val manBtn = IconView(this, IconView.SLIDERS).apply {
            background = glass(20)
            setOnClickListener {
                manual.visibility = if (manual.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                settings.visibility = View.GONE
                updateManual()
            }
        }
        controls.addView(manBtn, lp(dp(40), dp(40), Gravity.START or Gravity.CENTER_VERTICAL))

        manual = buildManual()
        manual.visibility = View.GONE
        controls.addView(manual, lp(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(52); rightMargin = dp(72); bottomMargin = dp(52)
        })

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

        add(segmented("stab", "Estabilización", listOf("off" to "Apagada", "standard" to "Estándar", "cinematic" to "Cinemática")) { v ->
            val e = StreamService.instance?.engine
            if (e != null && v !in e.stabModes()) {
                toast("Tu cámara no ofrece ese modo de estabilización")
                return@segmented
            }
            prefs.edit().putString("stab", v).apply()
            e?.setStabilization(v)
        })
        add(segmented("grid", "Cuadrícula", listOf("0" to "Apagada", "1" to "3×3", "2" to "Centro")) { v ->
            prefs.edit().putInt("grid", v.toInt()).apply()
            overlay.grid = v.toInt()
        })
        add(segmented("level", "Nivel de horizonte", listOf("off" to "Apagado", "on" to "Activo")) { v ->
            prefs.edit().putBoolean("level", v == "on").apply()
            overlay.levelOn = v == "on"
            updateLevelSensor()
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
        val diagBtn = label("Copiar informe de diagnóstico", 13f, INK).apply {
            gravity = Gravity.CENTER
            background = pill(Color.parseColor("#14000000"), 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { copyDiag() }
        }
        add(diagBtn)

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

    // ------------------------------------------------------------------ controles manuales

    private fun eng(): CameraEngine? = StreamService.instance?.engine

    private fun toProg(v: Double, lo: Double, hi: Double): Int =
        if (v <= lo || hi <= lo) 0 else (1000.0 * Math.log(v / lo) / Math.log(hi / lo)).toInt().coerceIn(0, 1000)

    private fun fromProg(p: Int, lo: Double, hi: Double): Double = lo * Math.pow(hi / lo, p / 1000.0)

    private fun fmtShutter(sec: Double): String = when {
        sec <= 0.0 -> "—"
        sec >= 0.5 -> String.format(Locale.US, "%.1fs", sec)
        else -> "1/${Math.round(1.0 / sec)}"
    }

    private fun toggleExposure() {
        val e = eng() ?: return
        if (!e.manualSupported()) { toast("Esta cámara no permite exposición manual"); return }
        e.setExposureManual(!e.exposureIsManual())
    }

    private fun buildManual(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = glass(18, true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val tint = ColorStateList.valueOf(INK)
        val track = ColorStateList.valueOf(Color.parseColor("#33000000"))

        fun col(key: String, title: String, max: Int, onChip: () -> Unit, onSeek: (Int) -> Unit) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val chip = label(title, 10f, INK, caps = true).apply {
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { onChip() }
            }
            val value = label("", 12f, INK)
            top.addView(chip)
            top.addView(value, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            val bar = SeekBar(this).apply {
                this.max = max
                progressTintList = tint
                thumbTintList = tint
                progressBackgroundTintList = track
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser && !updatingBars) onSeek(p)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            c.addView(top)
            c.addView(bar, LinearLayout.LayoutParams(-1, -2))
            row.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
            mBars[key] = bar; mValues[key] = value; mChips[key] = chip
        }

        col("iso", "ISO", 1000, { toggleExposure() }) { p ->
            val e = eng() ?: return@col
            val (lo, hi) = e.isoLimits()
            e.setIso(fromProg(p, lo.toDouble(), hi.toDouble()).toInt())
        }
        col("exp", "Obturador", 1000, { toggleExposure() }) { p ->
            val e = eng() ?: return@col
            val (lo, hi) = e.shutterLimits()
            e.setShutter(fromProg(p, lo, hi))
        }
        col("wb", "Blancos", 4, {
            val e = eng() ?: return@col
            e.setWbIndex(if (e.wbIndex() >= 0) -1 else 2)
        }) { p -> eng()?.setWbIndex(p) }
        col("focus", "Enfoque", 1000, {
            val e = eng() ?: return@col
            if (!e.focusSupported()) { toast("Esta cámara tiene el enfoque fijo"); return@col }
            e.setFocusManual(!e.focusIsManual())
        }) { p -> eng()?.setFocusPos(p / 1000f) }
        return row
    }

    private fun chipStyle(key: String, manualOn: Boolean) {
        mChips[key]?.background = pill(if (manualOn) Color.WHITE else Color.parseColor("#14000000"), 10)
    }

    private fun updateManual() {
        if (manual.visibility != View.VISIBLE) return
        val e = eng() ?: return
        updatingBars = true
        try {
            val man = e.exposureIsManual()
            val (iLo, iHi) = e.isoLimits()
            val (tLo, tHi) = e.shutterLimits()
            val iso = e.isoNow()
            val sh = e.shutterNow()
            mValues["iso"]?.text = if (iso > 0) iso.toString() else "—"
            mValues["exp"]?.text = fmtShutter(sh)
            val isoBar = mBars["iso"]; val expBar = mBars["exp"]
            if (isoBar != null && !isoBar.isPressed) isoBar.progress = toProg(iso.toDouble(), iLo.toDouble(), iHi.toDouble())
            if (expBar != null && !expBar.isPressed) expBar.progress = toProg(sh, tLo, tHi)
            isoBar?.alpha = if (man) 1f else 0.5f
            expBar?.alpha = if (man) 1f else 0.5f
            chipStyle("iso", man); chipStyle("exp", man)

            val wi = e.wbIndex()
            mValues["wb"]?.text = if (wi < 0) "Auto" else e.wbNames[wi]
            val wbBar = mBars["wb"]
            if (wbBar != null && !wbBar.isPressed) wbBar.progress = if (wi < 0) 2 else wi
            wbBar?.alpha = if (wi >= 0) 1f else 0.5f
            chipStyle("wb", wi >= 0)

            val fm = e.focusIsManual()
            val pos = e.focusPosNow()
            mValues["focus"]?.text = if (fm) "${(pos * 100).toInt()}%" else "AF"
            val fBar = mBars["focus"]
            if (fBar != null && !fBar.isPressed) fBar.progress = (pos * 1000).toInt()
            fBar?.alpha = if (fm) 1f else 0.5f
            chipStyle("focus", fm)
        } finally { updatingBars = false }
    }

    // ------------------------------------------------------------------ informe de diagnóstico

    private fun copyDiag() {
        val e = eng()
        toast("Generando informe…")
        Thread {
            val sb = StringBuilder()
            try {
                val v = packageManager.getPackageInfo(packageName, 0).versionName
                sb.append("LensLink Android $v\n")
            } catch (_: Exception) {}
            sb.append(e?.diagnostics()
                ?: ("Servidor apagado: enciéndelo para leer las capacidades de la cámara.\n" + DiagLog.dump()))
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("LensLink", sb.toString()))
                toast("Informe copiado: pégalo en el chat")
            }
        }.start()
    }

    // ------------------------------------------------------------------ nivel de horizonte

    private val sm by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    private val levelListener = object : SensorEventListener {
        override fun onSensorChanged(ev: SensorEvent) {
            val ax = ev.values[0]; val ay = ev.values[1]; val az = ev.values[2]
            // ejes del dispositivo -> ejes de la pantalla según la rotación
            val (sx, sy) = when (rotationDeg()) {
                90 -> ay to -ax
                270 -> -ay to ax
                180 -> -ax to -ay
                else -> ax to ay
            }
            val ang = Math.toDegrees(atan2(sx.toDouble(), sy.toDouble())).toFloat()
            overlay.setLevel(ang, abs(az) > 8.8f)   // casi tumbado: el nivel no tiene sentido
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun updateLevelSensor() {
        val want = overlay.levelOn && !dimmed && resumed
        if (want && !levelRegistered) {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(levelListener, it, SensorManager.SENSOR_DELAY_UI)
                levelRegistered = true
            }
        } else if (!want && levelRegistered) {
            sm.unregisterListener(levelListener)
            levelRegistered = false
        }
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
            val (pct, charging) = batteryInfo()
            if (dimSecs % 30 == 0) {
                // Cambia de sitio cada 30 s para no marcar la pantalla OLED.
                dimBattery.translationX = ((Math.random() - 0.5) * dp(160)).toFloat()
                dimBattery.translationY = ((Math.random() - 0.5) * dp(120)).toFloat()
            }
            dimBattery.text = if (pct < 0) "--" else "$pct%"
            val low = pct in 0..20 && !charging
            val blink = (System.currentTimeMillis() / 1000) % 15 < 5     // 5 s de cada 15 s
            dimBattery.setTextColor(when {
                low && blink && pct <= 14 -> Color.parseColor("#CCFF3B30")
                low && blink -> Color.parseColor("#CCFF9F0A")
                else -> Color.parseColor("#55FFFFFF")
            })
            dimSecs++
            ui.postDelayed(this, 1000)
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
        manual.visibility = View.GONE
        updateLevelSensor()
        dimLayer.visibility = View.VISIBLE
        setBrightness(0.01f)
        ui.removeCallbacks(tick)
        // Sin emitir no hace falta cámara abierta: se suelta la vista previa (la cámara se apaga).
        val s = StreamService.instance
        if (s != null && !s.engine.running) { s.engine.setPreview(null); previewAttached = false }
        dimSecs = 0
        ui.post(dimTick)
    }

    private fun wake() {
        if (!dimmed) return
        dimmed = false
        updateLevelSensor()
        dimLayer.visibility = View.GONE
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        ui.removeCallbacks(dimTick)
        dimBattery.translationX = 0f; dimBattery.translationY = 0f
        ui.post(tick)
        scheduleIdle()
    }

    /** (porcentaje, cargando). Porcentaje -1 si no se puede leer. */
    private fun batteryInfo(): Pair<Int, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1 to false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return (if (level < 0) -1 else level * 100 / max(scale, 1)) to charging
    }

    private fun batteryLabel(): String = batteryInfo().first.let { if (it < 0) "--" else "$it%" }

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
        val (bPct, bCharging) = batteryInfo()
        batteryText.text = if (bPct < 0) "--" else "$bPct%"
        val bLow = bPct in 0..20 && !bCharging
        val bBlink = (System.currentTimeMillis() / 1000) % 15 < 5       // 5 s de cada 15 s
        batteryBadge.tint = if (bPct in 0..14) RED else ORANGE          // rojo por debajo del 15 %
        batteryBadge.visibility = if (bLow && bBlink) View.VISIBLE else View.GONE

        // estado
        val dot = if (e?.running == true) "●" else "○"
        val heat = (if ((e?.thermalLevel ?: 0) >= 2) " · temp. alta" else "") +
            (if (e?.batteryLow == true) " · batería baja" else "")
        val txt = when {
            s == null -> "Servidor apagado"
            e != null && e.recovering -> "○  Recuperando cámara…"
            e!!.running -> "$dot  EN VIVO · ${e.resolution} · ${e.fps} · ${e.codec.uppercase()}" +
                (if (s.paused) " · pausa" else "") + (if (s.clientConnected) "" else " · sin OBS")
            s.clientConnected -> "$dot  OBS conectado"
            else -> "$dot  Esperando OBS"
        }
        val sb = SpannableStringBuilder(txt + heat)
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
        segSetters["stab"]?.invoke(e?.stabNow() ?: prefs.getString("stab", "standard") ?: "standard")
        if (e != null) dimOptions("stab", e.stabModes().toSet())
        segSetters["grid"]?.invoke(overlay.grid.toString())
        segSetters["level"]?.invoke(if (overlay.levelOn) "on" else "off")
        updateManual()
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
        resumed = true
        if (dimmed) wake() else { ui.post(tick); scheduleIdle() }
        updateLevelSensor()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        updateLevelSensor()
        ui.removeCallbacks(tick); ui.removeCallbacks(idle); ui.removeCallbacks(dimTick)
        dimmed = false
        dimLayer.visibility = View.GONE
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }
}
