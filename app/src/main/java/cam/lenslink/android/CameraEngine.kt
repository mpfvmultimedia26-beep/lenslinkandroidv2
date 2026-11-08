package cam.lenslink.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.TonemapCurve
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Camera2 -> MediaCodec (superficie) -> access units Annex B.
 * Toda la mutación de estado ocurre en el hilo `handler`.
 */
class CameraEngine(ctx: Context, private val cb: Callback) {

    interface Callback {
        /** La sesión está viva: hay que mandar VIDEO_CONFIG. */
        fun onStarted(width: Int, height: Int)
        fun onVideo(data: ByteArray, key: Boolean, ptsNs: Long)
        fun onStateChanged()
        fun onStopped(reason: String?)
    }

    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val camThread = HandlerThread("lenslink-camera").also { it.start() }
    private val encThread = HandlerThread("lenslink-encoder").also { it.start() }
    private val handler = Handler(camThread.looper)
    private val encHandler = Handler(encThread.looper)

    // ---- Ajustes (los pone la UI / el plugin) ----
    @Volatile var resolution = "1080p"
    @Volatile var fps = 60
    @Volatile var codec = "hevc"
    @Volatile var quality = "balanced"
    @Volatile var facingBack = true

    @Volatile private var zoom = 1f
    @Volatile private var exposureBias = 0f
    private var focusLocked = false
    private var lensPosition = 0f
    @Volatile private var torch = false
    private var wbMode = "auto"
    private var wbTemp = 5600
    private var awbLock = false
    private var aeLock = false
    private var isoManual = false
    private var shutterManual = false
    private var iso = 400
    private var shutter = 1.0 / 120.0
    private var stab = "standard"
    @Volatile var currentLook = "natural"
        private set
    private var meterX = -1
    private var meterY = -1
    @Volatile private var wantStream = false
    @Volatile private var previewBlocked = false
    private var previewSurface: Surface? = null
    private val activeTargets = mutableListOf<Surface>()
    @Volatile var lastError: String? = null

    // Recuperación automática
    @Volatile var recovering = false
        private set
    private var recoverAttempts = 0
    private val recoverDelays = longArrayOf(1000, 2000, 4000, 8000, 15000)
    private val retryRun = Runnable {
        if (wantStream || previewSurface?.isValid == true) reconfigure()
    }
    private val resetAttempts = Runnable { recoverAttempts = 0 }

    // Control de temperatura
    @Volatile var thermalLevel = 0
        private set
    @Volatile var thermalCapped = false
        private set
    private var thermalScale = 1.0
    @Volatile var batteryLow = false
        private set
    private var capsSuspended = false   // el usuario eligió formato a mano mientras había un límite
    private var savedFps: Int? = null
    private var savedRes: String? = null
    private fun capWanted() = thermalLevel >= 3 || batteryLow

    private val restoreRun = Runnable {
        if (!capWanted() && (savedFps != null || savedRes != null)) {
            val id = curId()
            val r = savedRes ?: resolution
            val f = savedFps ?: fps
            savedFps = null; savedRes = null; thermalCapped = false
            if (id != null && supports(id, r, f, codec)) {
                resolution = r; fps = f
                DiagLog.add("vuelve al formato elegido: $r ${f}fps")
                if (running) restartAsync()
            }
            cb.onStateChanged()
        }
    }

    // Últimos valores reales de la cámara (para mostrarlos y para pasar a manual sin saltos)
    @Volatile private var lastIso = 0
    @Volatile private var lastExpNs = 0L
    @Volatile private var lastFocusD = 0f
    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExpNs = it }
            res.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusD = it }
        }
    }

    // ---- Estado de la sesión ----
    private var chars: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var enc: MediaCodec? = null
    private var encSurface: Surface? = null
    private var csd: ByteArray? = null
    @Volatile private var tsRealtime = true
    @Volatile var running = false
        private set

    private var bitrateScale = 1.0
    private var lastBackpressure = 0L

    private val backId: String? by lazy { findId(CameraCharacteristics.LENS_FACING_BACK) }
    private val frontId: String? by lazy { findId(CameraCharacteristics.LENS_FACING_FRONT) }

    private fun findId(facing: Int): String? = try {
        mgr.cameraIdList.firstOrNull {
            val c = mgr.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.LENS_FACING) == facing &&
                (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true)
        }
    } catch (e: Exception) { null }

    private fun curId(): String? = (if (facingBack) backId else frontId) ?: backId ?: frontId

    /** Mismo dominio de reloj que los timestamps de los frames de la cámara. */
    fun clockNow(): Long = if (tsRealtime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()

    // ------------------------------------------------------------------ API pública

    /** Empieza a emitir (enciende el encoder). La vista previa, si hay, sigue igual. */
    fun start() {
        handler.post {
            wantStream = true
            recoverAttempts = 0; recovering = false
            handler.removeCallbacks(retryRun)
            DiagLog.add("emisión: $resolution ${fps}fps $codec")
            reconfigure()
        }
    }

    /** Deja de emitir; si hay superficie de vista previa, la cámara sigue abierta para verla. */
    fun stop() {
        handler.post {
            val was = wantStream
            wantStream = false
            previewBlocked = false
            recovering = false
            handler.removeCallbacks(retryRun)
            DiagLog.add("emisión detenida")
            reconfigure()
            if (was) cb.onStopped(null)
        }
    }

    private fun restartAsync() { handler.post { previewBlocked = false; reconfigure() } }

    /** Superficie donde se dibuja la vista previa (null = sin vista previa, ahorra batería). */
    fun setPreview(s: Surface?, wait: Boolean = false) {
        val latch = if (wait) CountDownLatch(1) else null
        handler.post {
            if (previewSurface !== s) {
                previewSurface = s
                reconfigure()
            }
            latch?.countDown()
        }
        latch?.await(1500, TimeUnit.MILLISECONDS)
    }
    fun requestKeyframe() {
        encHandler.post {
            try {
                enc?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            } catch (_: Exception) {}
        }
    }

    /**
     * Cambiar formato. Devuelve 0 = no soportado, 1 = aplicado, 2 = aplicado pero con menos fps
     * de los que había (p. ej. 4K60 no se ofrece por Camera2 en muchos móviles, sí 4K30).
     */
    fun setFormat(res: String?, fpsValue: Int?, codecValue: String?): Int {
        val r = res ?: resolution
        var f = fpsValue ?: fps
        val c = codecValue ?: codec
        val id = curId() ?: return 0
        var result = 1
        if (!supports(id, r, f, c)) {
            if (fpsValue == null && f > 30 && supports(id, r, 30, c)) { f = 30; result = 2 }
            else return 0
        }
        resolution = r; fps = f; codec = c
        savedFps = null; savedRes = null; thermalCapped = false
        if (capWanted()) capsSuspended = true
        DiagLog.add("formato: $r ${f}fps $c")
        if (running) restartAsync() else cb.onStateChanged()
        return result
    }

    /** resolución -> fps que cámara y encoder admiten a la vez (con el códec actual). */
    fun formatMatrix(): Map<String, List<Int>> {
        val id = curId() ?: return emptyMap()
        return listOf("720p", "1080p", "4K").associateWith { r -> listOf(30, 60).filter { supports(id, r, it, codec) } }
    }

    fun changeQuality(q: String) {
        if (q != "balanced" && q != "maximum") return
        quality = q
        if (running) restartAsync() else cb.onStateChanged()
    }

    /** Llamado cuando el enlace va justo: baja el bitrate un 20 %. */
    fun onBackpressure() {
        handler.post {
            lastBackpressure = SystemClock.elapsedRealtime()
            bitrateScale = (bitrateScale * 0.8).coerceAtLeast(0.3)
            applyBitrate()
        }
    }

    private val recover = object : Runnable {
        override fun run() {
            if (running && bitrateScale < 1.0 &&
                SystemClock.elapsedRealtime() - lastBackpressure > 6000) {
                bitrateScale = (bitrateScale * 1.1).coerceAtMost(1.0)
                applyBitrate()
            }
            handler.postDelayed(this, 3000)
        }
    }

    init { handler.postDelayed(recover, 3000) }

    fun release() {
        handler.post { stopInternal() }
        camThread.quitSafely()
        encThread.quitSafely()
    }

    // ------------------------------------------------------------------ capacidades

    private fun sizeOf(res: String): Size = when (res) {
        "720p" -> Size(1280, 720)
        "4K" -> Size(3840, 2160)
        else -> Size(1920, 1080)
    }

    private fun mimeOf(c: String) =
        if (c == "hevc") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    private fun encoderSupports(mime: String, w: Int, h: Int, f: Int): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } &&
                (info.getCapabilitiesForType(mime).videoCapabilities
                    ?.areSizeAndRateSupported(w, h, f.toDouble()) == true)
        }
    } catch (e: Exception) { false }

    private val capCache = HashMap<String, Boolean>()

    @Synchronized
    private fun supports(id: String, res: String, f: Int, c: String): Boolean =
        capCache.getOrPut("$id|$res|$f|$c") { supportsUncached(id, res, f, c) }

    private fun supportsUncached(id: String, res: String, f: Int, c: String): Boolean = try {
        val sz = sizeOf(res)
        val ch = mgr.getCameraCharacteristics(id)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        var ok = map != null && (map.getOutputSizes(MediaCodec::class.java)?.contains(sz) == true)
        if (ok) {
            val minDur = map!!.getOutputMinFrameDuration(MediaCodec::class.java, sz)
            if (minDur > 0 && minDur > 1_000_000_000L / f + 1_000_000L) ok = false
        }
        if (ok) {
            val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            if (ranges != null && ranges.none { it.upper >= f }) ok = false
        }
        ok && encoderSupports(mimeOf(c), sz.width, sz.height, f)
    } catch (e: Exception) { false }

    private fun supportedResolutions(id: String): List<String> =
        listOf("720p", "1080p", "4K").filter { r -> listOf(30, 60).any { supports(id, r, it, codec) } }

    private fun supportedFps(id: String): List<Int> =
        listOf(30, 60).filter { supports(id, resolution, it, codec) }

    private fun supportedCodecs(id: String): List<String> =
        listOf("h264", "hevc").filter { supports(id, resolution, fps, it) || supports(id, "1080p", 30, it) }

    /** Si lo pedido no se puede, baja fps y luego resolución hasta algo que funcione. */
    private fun negotiate(id: String) {
        if (supports(id, resolution, fps, codec)) return
        for (f in listOf(fps, 30)) for (r in listOf(resolution, "1080p", "720p")) {
            if (supports(id, r, f, codec)) { resolution = r; fps = f; return }
        }
        if (codec == "hevc" && supports(id, "1080p", 30, "h264")) { codec = "h264"; resolution = "1080p"; fps = 30 }
    }

    // ------------------------------------------------------------------ arranque / parada

    @SuppressLint("MissingPermission")
    private fun reconfigure() {
        stopInternal()
        val pv = previewSurface
        val withPreview = pv != null && pv.isValid && !(wantStream && previewBlocked)
        if (!wantStream && !withPreview) return
        val id = curId()
        if (id == null) { fail("No se encontró cámara", retry = false); return }
        try {
            val ch = mgr.getCameraCharacteristics(id)
            chars = ch
            tsRealtime = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            var sz = Size(0, 0)
            if (wantStream) {
                negotiate(id)
                sz = sizeOf(resolution)
                encSurface = startEncoder(sz.width, sz.height)
            }
            val finalSz = sz
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession(camera, finalSz, withPreview)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); fail("Cámara desconectada")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); fail("Error de cámara $error")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            fail("No se pudo iniciar: ${e.message}")
        }
    }

    /** Falla de cámara/encoder. Si era recuperable, se reintenta con espera creciente. */
    private fun fail(msg: String, retry: Boolean = true) {
        DiagLog.add("fallo: $msg")
        if (stab == "cinematic" && msg.startsWith("Error de cámara")) {
            stab = "standard"
            DiagLog.add("la estabilización cinematográfica falló: se usa la estándar")
        }
        val wasStreaming = wantStream
        val wantsPreview = previewSurface?.isValid == true
        stopInternal()
        lastError = msg
        handler.removeCallbacks(retryRun)
        if (retry && (wasStreaming || wantsPreview) && recoverAttempts < recoverDelays.size) {
            val d = recoverDelays[recoverAttempts++]
            recovering = true
            lastError = "$msg · reintentando en ${d / 1000}s"
            DiagLog.add("reintento $recoverAttempts/${recoverDelays.size} en ${d / 1000}s")
            handler.postDelayed(retryRun, d)
            cb.onStateChanged()
            return
        }
        recovering = false
        wantStream = false
        if (wasStreaming) cb.onStopped(msg) else cb.onStateChanged()
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice, sz: Size, withPreview: Boolean) {
        val targets = mutableListOf<Surface>()
        encSurface?.let { targets += it }
        val pv = previewSurface
        val usePreview = withPreview && pv != null && pv.isValid
        if (usePreview) targets += pv!!
        if (targets.isEmpty()) { fail("Sin destino de vídeo", retry = false); return }
        try {
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    activeTargets.clear()
                    activeTargets.addAll(targets)
                    if (!previewBlocked) lastError = null
                    if (recovering) DiagLog.add("recuperado")
                    recovering = false
                    handler.removeCallbacks(resetAttempts)
                    handler.postDelayed(resetAttempts, 20_000)
                    applyRequest()
                    if (encSurface != null) {
                        running = true
                        cb.onStarted(sz.width, sz.height)
                    }
                    cb.onStateChanged()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    try { s.close() } catch (_: Exception) {}
                    if (usePreview && encSurface != null) {
                        // 4K60 + vista previa puede no caber: se reintenta solo con el encoder.
                        previewBlocked = true
                        lastError = "Vista previa desactivada: $resolution ${fps}fps no cabe con ella"
                        createSession(camera, sz, false)
                    } else {
                        fail("La cámara no admite $resolution ${fps}fps", retry = false)
                    }
                }
            }, handler)
        } catch (e: Exception) {
            fail("Sesión fallida: ${e.message}")
        }
    }

    private fun stopInternal() {
        running = false
        activeTargets.clear()
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { enc?.stop() } catch (_: Exception) {}
        try { enc?.release() } catch (_: Exception) {}
        enc = null
        try { encSurface?.release() } catch (_: Exception) {}
        encSurface = null
        csd = null
    }

    // ------------------------------------------------------------------ encoder

    private fun baseBitrate(): Int {
        val h264 = when (resolution) { "720p" -> 4_000_000; "4K" -> 30_000_000; else -> 8_000_000 }
        val base = if (codec == "hevc") h264 * 6 / 10 else h264
        val perFps = if (fps <= 60) base else base * 3 / 2
        val q = if (quality == "maximum") 1.5 else 1.0
        return (perFps * q).toInt()
    }

    private fun currentBitrate() = (baseBitrate() * bitrateScale * thermalScale).toInt()

    private fun applyBitrate() {
        encHandler.post {
            try {
                enc?.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, currentBitrate())
                })
            } catch (_: Exception) {}
        }
    }

    private fun encoderCallback() = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {}
        override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {}
        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "encoder error", e)
            handler.post { fail("Error del encoder") }
        }
        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buf = c.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        csd = bytes // VPS/SPS/PPS (o SPS/PPS) en Annex B
                    } else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val params = csd
                        // Cada keyframe debe ser autocontenido: parameter sets delante del IDR.
                        val out = if (key && params != null && !startsWithParamSets(bytes))
                            params + bytes else bytes
                        cb.onVideo(out, key, info.presentationTimeUs * 1000L)
                    }
                }
            } finally {
                try { c.releaseOutputBuffer(index, false) } catch (_: Exception) {}
            }
        }
    }

    private fun startEncoder(w: Int, h: Int): Surface {
        bitrateScale = 1.0
        val mime = mimeOf(codec)
        fun format(cbr: Boolean) = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, currentBitrate())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }

        var codecObj = MediaCodec.createEncoderByType(mime)
        codecObj.setCallback(encoderCallback(), encHandler)
        try {
            codecObj.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Algunos encoders no aceptan CBR: se reintenta con el modo por defecto.
            try { codecObj.release() } catch (_: Exception) {}
            codecObj = MediaCodec.createEncoderByType(mime)
            codecObj.setCallback(encoderCallback(), encHandler)
            codecObj.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surface = codecObj.createInputSurface()
        codecObj.start()
        enc = codecObj
        return surface
    }

    /** ¿El access unit ya empieza por VPS/SPS? (HEVC tipo 32, H.264 tipo 7) */
    private fun startsWithParamSets(b: ByteArray): Boolean {
        var i = 0
        while (i < b.size - 4 && b[i].toInt() == 0) i++
        if (i >= b.size - 1 || b[i].toInt() != 1) return false
        val nal = b[i + 1].toInt()
        return if (codec == "hevc") ((nal shr 1) and 0x3F) == 32 else (nal and 0x1F) == 7
    }

    // ------------------------------------------------------------------ petición de captura

    private fun pickFpsRange(ch: CameraCharacteristics, target: Int): Range<Int>? {
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == target && it.upper == target }
            ?: ranges.filter { it.upper == target }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper >= target }.minByOrNull { it.upper }
    }

    private fun zoomRange(ch: CameraCharacteristics): Range<Float> =
        if (Build.VERSION.SDK_INT >= 30)
            ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
        else Range(1f, ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)

    private fun awbPreset(ch: CameraCharacteristics, t: Int): Int {
        val want = when {
            t < 3500 -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
            t < 4500 -> CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT
            t < 5800 -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
            t < 7000 -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            else -> CameraMetadata.CONTROL_AWB_MODE_TWILIGHT
        }
        val avail = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        return if (avail.contains(want)) want else CameraMetadata.CONTROL_AWB_MODE_AUTO
    }

    private fun hasManualSensor(ch: CameraCharacteristics) =
        ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

    private fun applyRequest() {
        val s = session ?: return
        val d = device ?: return
        if (activeTargets.isEmpty()) return
        val ch = chars ?: return
        try {
            val r = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            activeTargets.forEach { r.addTarget(it) }
            r.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            // Sin encoder (solo vista previa) basta con 30 fps: menos consumo.
            pickFpsRange(ch, if (encSurface != null) fps else minOf(fps, 30))
                ?.let { r.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

            // Zoom
            val zr = zoomRange(ch)
            val z = zoom.coerceIn(zr.lower, zr.upper)
            if (Build.VERSION.SDK_INT >= 30) {
                r.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
            } else {
                val a = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
                val cw = (a.width() / z).toInt(); val chh = (a.height() / z).toInt()
                val x = a.centerX() - cw / 2; val y = a.centerY() - chh / 2
                r.set(CaptureRequest.SCALER_CROP_REGION, Rect(x, y, x + cw, y + chh))
            }

            // Exposición
            if (isoManual && shutterManual && hasManualSensor(ch)) {
                r.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                r.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                r.set(CaptureRequest.SENSOR_EXPOSURE_TIME, (shutter * 1e9).toLong())
                r.set(CaptureRequest.SENSOR_FRAME_DURATION, (1e9 / fps).toLong())
            } else {
                r.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                val step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
                val range = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                if (step > 0f && range != null) {
                    r.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        Math.round(exposureBias / step).coerceIn(range.lower, range.upper))
                }
                r.set(CaptureRequest.CONTROL_AE_LOCK, aeLock)
            }

            // Enfoque
            val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (focusLocked && minFocus > 0f) {
                r.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                // iOS: 0 = cerca, 1 = lejos. Android: dioptrías, 0 = infinito.
                r.set(CaptureRequest.LENS_FOCUS_DISTANCE, (1f - lensPosition.coerceIn(0f, 1f)) * minFocus)
            } else {
                r.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }

            // Balance de blancos
            if (wbMode == "locked") {
                r.set(CaptureRequest.CONTROL_AWB_MODE, awbPreset(ch, wbTemp))
            } else {
                r.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                r.set(CaptureRequest.CONTROL_AWB_LOCK, awbLock)
            }

            // Linterna
            if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                r.set(CaptureRequest.FLASH_MODE,
                    if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            }

            // Estabilización
            val vModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
            val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
            var eis = 0
            if (stab == "cinematic" && vModes.contains(2)) eis = 2 // PREVIEW_STABILIZATION (API 33)
            else if (stab != "off" && vModes.contains(1)) eis = 1
            r.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eis)
            if (eis == 0 && ois.contains(1)) {
                r.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, if (stab == "off") 0 else 1)
            }

            applyLook(r, ch)
            applyRegions(r, ch)

            s.setRepeatingRequest(r.build(), captureCb, handler)
        } catch (e: Exception) {
            Log.w(TAG, "apply failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ looks y regiones

    private fun supportsCurve(ch: CameraCharacteristics) =
        ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
            ?.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) == true

    private fun srgb(x: Float): Float =
        if (x <= 0.0031308f) 12.92f * x else (1.055 * x.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()

    private fun smooth(y: Float) = y * y * (3f - 2f * y)

    /** y = salida ya con gamma sRGB; se le añade contraste en S según el look. */
    private fun shape(look: String, y: Float): Float = when (look) {
        "contrast" -> y + 0.45f * (smooth(y) - y)
        "vivid" -> (y + 0.75f * (smooth(y) - y)) * 1.04f
        "cine" -> 0.04f + 0.92f * (y + 0.20f * (smooth(y) - y))
        else -> y
    }

    private fun lookCurve(look: String, n: Int): TonemapCurve {
        val pts = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            val x = t * t                       // más puntos en sombras
            pts[2 * i] = x
            pts[2 * i + 1] = shape(look, srgb(x)).coerceIn(0f, 1f)
        }
        return TonemapCurve(pts, pts, pts)
    }

    private fun applyLook(r: CaptureRequest.Builder, ch: CameraCharacteristics) {
        when (currentLook) {
            "natural" -> {}
            "mono" -> {
                val fx = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS) ?: intArrayOf()
                if (fx.contains(CameraMetadata.CONTROL_EFFECT_MODE_MONO))
                    r.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_MONO)
            }
            else -> if (supportsCurve(ch)) {
                val n = minOf(33, ch.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 33).coerceAtLeast(2)
                r.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                r.set(CaptureRequest.TONEMAP_CURVE, lookCurve(currentLook, n))
            }
        }
    }

    private fun applyRegions(r: CaptureRequest.Builder, ch: CameraCharacteristics) {
        if (meterX < 0) return
        val a = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val half = (minOf(a.width(), a.height()) * 0.08f).toInt()
        val x = (meterX - half).coerceIn(a.left, a.right - 2 * half)
        val y = (meterY - half).coerceIn(a.top, a.bottom - 2 * half)
        val rect = MeteringRectangle(x, y, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        if ((ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0)
            r.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
        if ((ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0)
            r.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
    }

    // ------------------------------------------------------------------ API para la interfaz

    private fun anyChars(): CameraCharacteristics? = chars ?: curId()?.let {
        try { mgr.getCameraCharacteristics(it) } catch (_: Exception) { null }
    }

    private fun pushChange() { handler.post { applyRequest(); cb.onStateChanged() } }

    fun lensList(): List<Pair<String, Float>> = anyChars()?.let { lensChoices(it) } ?: emptyList()
    fun zoomNow(): Float = zoom
    fun biasNow(): Float = exposureBias
    fun hasTorch(): Boolean = anyChars()?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    fun torchOn(): Boolean = torch

    fun zoomTo(z: Float) {
        val r = anyChars()?.let { zoomRange(it) }
        zoom = if (r != null) z.coerceIn(r.lower, r.upper) else z
        pushChange()
    }

    fun biasLimits(): Pair<Float, Float> {
        val ch = anyChars() ?: return -2f to 2f
        val step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: return 0f to 0f
        val rg = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return 0f to 0f
        return (rg.lower * step) to (rg.upper * step)
    }

    fun biasTo(ev: Float) {
        val (lo, hi) = biasLimits()
        exposureBias = ev.coerceIn(lo, hi)
        pushChange()
    }

    fun setTorch(on: Boolean) { torch = on; pushChange() }
    fun lockAeAwb(on: Boolean) { handler.post { aeLock = on; awbLock = on; applyRequest() } }

    fun setLook(id: String) { currentLook = id; pushChange() }

    fun looks(): List<Pair<String, String>> {
        val ch = anyChars() ?: return listOf("natural" to "Natural")
        val out = mutableListOf("natural" to "Natural")
        if (supportsCurve(ch)) {
            out += ("contrast" to "Contraste")
            out += ("vivid" to "Intenso")
            out += ("cine" to "Cine")
        }
        val fx = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS) ?: intArrayOf()
        if (fx.contains(CameraMetadata.CONTROL_EFFECT_MODE_MONO)) out += ("mono" to "B/N")
        return out
    }

    fun flip() {
        handler.post {
            facingBack = !facingBack
            zoom = 1f; torch = false; meterX = -1; aeLock = false; awbLock = false
            reconfigure()
            cb.onStateChanged()
        }
    }

    /**
     * Enfoque/medición en un punto tocado. (nx, ny) = posición normalizada en la pantalla
     * (ya relativa al rectángulo de la vista previa); displayDeg = rotación de la pantalla.
     */
    fun focusAt(nx: Float, ny: Float, displayDeg: Int) {
        handler.post {
            val ch = chars ?: return@post
            val a = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
            val so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val rel = if (facingBack) (so - displayDeg + 360) % 360 else (so + displayDeg) % 360
            val (u, v) = when (rel) {
                0 -> nx to ny
                90 -> ny to (1f - nx)
                180 -> (1f - nx) to (1f - ny)
                else -> (1f - ny) to nx
            }
            val z = maxOf(zoom, 1f)
            meterX = (a.centerX() + (u - 0.5f) * a.width() / z).toInt()
            meterY = (a.centerY() + (v - 0.5f) * a.height() / z).toInt()
            focusLocked = false; aeLock = false; awbLock = false
            applyRequest()
            cb.onStateChanged()
        }
    }

    // ------------------------------------------------------------------ temperatura

    /**
     * Reevalúa los límites automáticos. Calor severo (>=3) o batería baja: máx. 30 fps.
     * Calor crítico (>=4) o batería baja: 4K baja a 1080p. Al desaparecer el motivo, vuelve a lo elegido tras 1 min.
     */
    private fun reevaluateCaps() {
        handler.removeCallbacks(restoreRun)
        if (!capWanted()) capsSuspended = false
        if (capsSuspended) return
        val id = curId()
        val wantFps30 = thermalLevel >= 3 || batteryLow
        val want1080 = thermalLevel >= 4 || batteryLow
        if (id != null && (wantFps30 || want1080)) {
            var changed = false
            if (wantFps30 && fps > 30 && supports(id, resolution, 30, codec)) {
                if (savedFps == null) savedFps = fps
                fps = 30; changed = true
            }
            if (want1080 && resolution == "4K" && supports(id, "1080p", fps, codec)) {
                if (savedRes == null) savedRes = resolution
                resolution = "1080p"; changed = true
            }
            if (changed) {
                thermalCapped = true
                DiagLog.add("límite automático: $resolution ${fps}fps (calor=$thermalLevel, batería baja=$batteryLow)")
                if (running) restartAsync()
            }
        } else if (savedFps != null || savedRes != null) {
            handler.postDelayed(restoreRun, 60_000)
        }
    }

    /** Nivel de PowerManager.getCurrentThermalStatus(): 0 normal ... 3 severo, 4+ crítico. */
    fun setThermalLevel(level: Int) {
        handler.post {
            thermalLevel = level
            DiagLog.add("temperatura: nivel $level")
            thermalScale = when { level >= 3 -> 0.5; level == 2 -> 0.7; else -> 1.0 }
            if (running) applyBitrate()
            reevaluateCaps()
            cb.onStateChanged()
        }
    }

    /** Lo decide el servicio: batería <= 20 % sin cargar (se levanta con >= 25 % o al enchufar). */
    fun setBatteryLow(low: Boolean) {
        handler.post {
            if (batteryLow == low) return@post
            batteryLow = low
            DiagLog.add("batería baja: $low")
            reevaluateCaps()
            cb.onStateChanged()
        }
    }

    // ------------------------------------------------------------------ estabilización

    fun stabNow(): String = stab

    /** Modos que esta cámara ofrece: off siempre; standard (EIS u OIS); cinematic (estabilización de vista previa, API 33+). */
    fun stabModes(): List<String> {
        val ch = anyChars() ?: return listOf("off", "standard")
        val v = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        val out = mutableListOf("off")
        if (v.contains(1) || ois.contains(1)) out += "standard"
        if (v.contains(2)) out += "cinematic"
        return out
    }

    fun setStabilization(mode: String) {
        stab = mode
        DiagLog.add("estabilización: $mode")
        pushChange()
    }

    // ------------------------------------------------------------------ controles manuales

    val wbNames = listOf("Incandescente", "Fluorescente", "Luz día", "Nublado", "Sombra")
    private val wbTemps = intArrayOf(2800, 4000, 5200, 6200, 7500)

    fun manualSupported(): Boolean = anyChars()?.let { hasManualSensor(it) } == true

    fun isoLimits(): Pair<Int, Int> =
        anyChars()?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower to it.upper } ?: (50 to 3200)

    fun shutterLimits(): Pair<Double, Double> {
        val r = anyChars()?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val lo = (r?.lower ?: 100_000L) / 1e9
        val hi = minOf((r?.upper ?: 100_000_000L) / 1e9, 1.0 / maxOf(fps, 1))
        return lo to maxOf(hi, lo * 2)
    }

    fun exposureIsManual(): Boolean = isoManual && shutterManual
    fun isoNow(): Int = if (exposureIsManual()) iso else lastIso
    fun shutterNow(): Double = if (exposureIsManual()) shutter else lastExpNs / 1e9

    private fun enterManualExposure() {
        if (isoManual && shutterManual) return
        val (lo, hi) = isoLimits()
        if (lastIso > 0) iso = lastIso.coerceIn(lo, hi)
        if (lastExpNs > 0) {
            val (tl, th) = shutterLimits()
            shutter = (lastExpNs / 1e9).coerceIn(tl, th)
        }
        isoManual = true; shutterManual = true
    }

    fun setExposureManual(on: Boolean) {
        handler.post {
            if (on && manualSupported()) enterManualExposure() else { isoManual = false; shutterManual = false }
            applyRequest(); cb.onStateChanged()
        }
    }

    fun setIso(v: Int) {
        handler.post {
            if (!manualSupported()) return@post
            enterManualExposure()
            val (lo, hi) = isoLimits()
            iso = v.coerceIn(lo, hi)
            applyRequest(); cb.onStateChanged()
        }
    }

    fun setShutter(sec: Double) {
        handler.post {
            if (!manualSupported()) return@post
            enterManualExposure()
            val (lo, hi) = shutterLimits()
            shutter = sec.coerceIn(lo, hi)
            applyRequest(); cb.onStateChanged()
        }
    }

    /** -1 = automático; 0..4 = preajuste (incandescente ... sombra). */
    fun wbIndex(): Int {
        if (wbMode != "locked") return -1
        return wbTemps.indices.minByOrNull { Math.abs(wbTemps[it] - wbTemp) } ?: 2
    }

    fun setWbIndex(i: Int) {
        handler.post {
            if (i < 0) { wbMode = "auto"; awbLock = false }
            else { wbMode = "locked"; wbTemp = wbTemps[i.coerceIn(0, wbTemps.size - 1)]; awbLock = false }
            applyRequest(); cb.onStateChanged()
        }
    }

    fun focusSupported(): Boolean =
        (anyChars()?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f

    fun focusIsManual(): Boolean = focusLocked

    /** 0 = cerca, 1 = lejos (igual que la app de iPhone). */
    fun focusPosNow(): Float {
        if (focusLocked) return lensPosition
        val minD = anyChars()?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        return if (minD > 0f) (1f - lastFocusD / minD).coerceIn(0f, 1f) else 1f
    }

    fun setFocusManual(on: Boolean) {
        handler.post {
            if (on) {
                if (!focusSupported()) return@post
                lensPosition = focusPosNow()
                meterX = -1
            }
            focusLocked = on
            applyRequest(); cb.onStateChanged()
        }
    }

    fun setFocusPos(p: Float) {
        handler.post {
            if (!focusSupported()) return@post
            lensPosition = p.coerceIn(0f, 1f)
            focusLocked = true
            applyRequest(); cb.onStateChanged()
        }
    }

    // ------------------------------------------------------------------ diagnóstico

    fun diagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Estado: $resolution ${fps}fps $codec · calidad $quality · emitiendo=$running · recuperando=$recovering")
        sb.appendLine("Temperatura: nivel $thermalLevel · limitado=$thermalCapped · vista previa bloqueada=$previewBlocked")
        sb.appendLine("Último aviso: ${lastError ?: "ninguno"}")
        sb.appendLine()
        val ids = try { mgr.cameraIdList.toList() } catch (e: Exception) { emptyList() }
        sb.appendLine("Cámaras (${ids.size}): ${ids.joinToString()}   · en uso: ${curId()}")
        for (id in ids) try {
            val ch = mgr.getCameraCharacteristics(id)
            val face = when (ch.get(CameraCharacteristics.LENS_FACING)) { 0 -> "frontal"; 1 -> "trasera"; else -> "externa" }
            val lvl = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                0 -> "LIMITED"; 1 -> "FULL"; 2 -> "LEGACY"; 3 -> "LEVEL_3"; else -> "?"
            }
            val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString(" ") { "${it.lower}-${it.upper}" }
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.joinToString(",") ?: "-"
            sb.appendLine("· id $id ($face, $lvl, orient ${ch.get(CameraCharacteristics.SENSOR_ORIENTATION)}°)")
            sb.appendLine("    fps AE: ${ranges ?: "-"}")
            sb.appendLine("    capacidades: $caps")
            sb.appendLine("    zoom: ${zoomRange(ch)} · curva de tonos: ${supportsCurve(ch)} · manual: ${hasManualSensor(ch)}")
            sb.appendLine("    estabilización de vídeo: ${ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.joinToString(",") ?: "-"}" +
                " (0 apagada, 1 normal, 2 vista previa) · óptica: ${ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.joinToString(",") ?: "-"}")
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            for (r in listOf("720p", "1080p", "4K")) {
                val sz = sizeOf(r)
                val has = map?.getOutputSizes(MediaCodec::class.java)?.contains(sz) == true
                val dur = if (has) map!!.getOutputMinFrameDuration(MediaCodec::class.java, sz) else 0L
                val max = if (dur > 0) "máx ≈ ${Math.round(1e9 / dur)} fps" else "sin dato"
                sb.appendLine("    $r por MediaCodec: ${if (has) "sí, $max" else "NO ofrecido"}")
            }
        } catch (e: Exception) { sb.appendLine("· id $id: error leyendo (${e.message})") }
        sb.appendLine()
        val id = curId()
        if (id != null) {
            sb.appendLine("Formatos que admiten cámara + encoder (id $id):")
            for (c in listOf("h264", "hevc")) for (r in listOf("720p", "1080p", "4K")) {
                val ok = listOf(30, 60).filter { supports(id, r, it, c) }
                sb.appendLine("    $c $r: ${if (ok.isEmpty()) "no" else ok.joinToString(" y ") + " fps"}")
            }
        }
        sb.appendLine()
        sb.appendLine("Últimos eventos:")
        sb.appendLine(DiagLog.dump())
        return sb.toString()
    }

    // ------------------------------------------------------------------ lentes

    private fun fmtF(x: Float) =
        if (x == x.toInt().toFloat()) x.toInt().toString() else String.format(Locale.US, "%.1f", x)

    /** En Android las lentes se alcanzan con el zoom ratio de la cámara lógica (API 30+). */
    private fun lensChoices(ch: CameraCharacteristics): List<Pair<String, Float>> {
        if (!facingBack) return listOf("Front" to 1f)
        val r = zoomRange(ch)
        val out = mutableListOf<Pair<String, Float>>()
        if (r.lower < 0.95f) out += "Ultra Wide (${fmtF(r.lower)}×)" to r.lower
        out += "Main (Wide)" to 1f
        val t = when { r.upper >= 5f -> 5f; r.upper >= 3f -> 3f; r.upper >= 2f -> 2f; else -> 0f }
        if (t > 0f) out += "Telephoto (${fmtF(t)}×)" to t
        return out
    }

    // ------------------------------------------------------------------ CONTROL

    fun handleControl(c: JSONObject) {
        handler.post {
            var restart = false
            when (c.optString("cmd")) {
                "zoom" -> zoom = c.optDouble("value", zoom.toDouble()).toFloat()
                "exposure_bias" -> exposureBias = c.optDouble("value", exposureBias.toDouble()).toFloat()
                "focus" -> {
                    if (c.has("mode")) focusLocked = c.optString("mode") == "locked"
                    if (c.has("lensPosition")) lensPosition = c.optDouble("lensPosition", 0.0).toFloat()
                }
                "flashlight" -> if (c.has("on")) torch = c.optBoolean("on")
                "flip" -> { facingBack = !facingBack; zoom = 1f; restart = true }
                "selectLens" -> {
                    val label = c.optString("label")
                    chars?.let { ch -> lensChoices(ch).firstOrNull { it.first == label }?.let { zoom = it.second } }
                }
                "white_balance" -> when (c.optString("mode")) {
                    "locked" -> { wbMode = "locked"; wbTemp = c.optInt("temperature", wbTemp); awbLock = false }
                    "calibrate" -> { wbMode = "auto"; awbLock = true }   // congela lo que el auto ve ahora
                    else -> { wbMode = "auto"; awbLock = false }
                }
                "lock" -> {
                    val on = c.optBoolean("on")
                    when (c.optString("target")) {
                        "whiteBalance" -> { wbMode = "auto"; awbLock = on }
                        "exposure" -> aeLock = on
                        "all" -> { awbLock = on; aeLock = on; if (!on) wbMode = "auto" }
                    }
                }
                "stabilization" -> stab = c.optString("mode", stab)
                "exposure" -> {
                    val m = c.optString("mode")
                    if (m == "manual") { isoManual = true; shutterManual = true }
                    if (m == "auto") { isoManual = false; shutterManual = false }
                    if (c.has("isoMode")) isoManual = c.optString("isoMode") == "manual"
                    if (c.has("shutterMode")) shutterManual = c.optString("shutterMode") == "manual"
                    if (c.has("iso")) iso = c.optInt("iso", iso)
                    if (c.has("shutterSeconds")) shutter = c.optDouble("shutterSeconds", shutter)
                }
                else -> return@post // set_format, tally, etc. los trata el servicio
            }
            if (restart) { meterX = -1; reconfigure() } else applyRequest()
            cb.onStateChanged()
        }
    }

    // ------------------------------------------------------------------ STATE

    /** Snapshot compacto. Los plugins viejos truncan en 1 KiB, así que se recorta si hace falta. */
    @Synchronized
    fun stateJson(paused: Boolean): JSONObject {
        val id = curId()
        val ch = chars ?: id?.let { try { mgr.getCameraCharacteristics(it) } catch (e: Exception) { null } }
        val j = JSONObject()
        j.put("camera", if (facingBack) "back" else "front")
        j.put("resolution", resolution); j.put("fps", fps); j.put("codec", codec); j.put("quality", quality)
        j.put("zoom", zoom.toDouble()); j.put("exposureBias", exposureBias.toDouble())
        j.put("focusMode", if (focusLocked) "locked" else "auto")
        j.put("lensPosition", lensPosition.toDouble())
        j.put("stabilization", stab)
        if (torch) j.put("flashlight", true)
        if (paused) { j.put("paused", true); j.put("pauseReason", "user") }
        if (ch != null && id != null) {
            val zr = zoomRange(ch)
            j.put("maxZoom", zr.upper.toDouble())
            if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) j.put("hasFlashlight", true)
            val lenses = lensChoices(ch)
            val cur = lenses.lastOrNull { it.second <= zoom + 0.05f } ?: lenses.first()
            j.put("lens", cur.first)
            j.put("lenses", JSONArray(lenses.map { it.first }))
            j.put("lensFactors", JSONArray(lenses.map { it.second.toDouble() }))
            j.put("resolutions", JSONArray(supportedResolutions(id)))
            j.put("frameRates", JSONArray(supportedFps(id)))
            j.put("codecs", JSONArray(supportedCodecs(id)))
            j.put("whiteBalanceMode", if (wbMode == "locked" || awbLock) "locked" else "auto")
            j.put("whiteBalanceTemperature", wbTemp)
            if (hasManualSensor(ch)) {
                j.put("supportsManualExposure", true)
                j.put("exposureMode", if (isoManual && shutterManual) "manual" else "auto")
                j.put("isoMode", if (isoManual) "manual" else "auto")
                j.put("shutterMode", if (shutterManual) "manual" else "auto")
                j.put("iso", iso); j.put("shutterSeconds", shutter)
                ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                    j.put("minISO", it.lower); j.put("maxISO", it.upper)
                }
                ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                    j.put("minShutterSeconds", it.lower / 1e9)
                    j.put("maxShutterSeconds", minOf(it.upper / 1e9, 1.0 / fps))
                }
            }
        }
        // Recorte por prioridad si pasa de 1 KiB.
        for (k in listOf("minShutterSeconds", "maxShutterSeconds", "minISO", "maxISO", "stabilization",
            "lensPosition", "whiteBalanceTemperature", "supportsManualExposure")) {
            if (j.toString().toByteArray().size <= 960) break
            j.remove(k)
        }
        return j
    }

    companion object { private const val TAG = "LensLink.cam" }
}
