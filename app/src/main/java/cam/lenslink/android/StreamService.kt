package cam.lenslink.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Servicio en primer plano: servidor TCP 9979 + anuncio Bonjour + cámara.
 * Equivale al "lado teléfono" de docs/PROTOCOL.md.
 */
class StreamService : Service(), StreamServer.Host, CameraEngine.Callback {

    companion object {
        @Volatile var instance: StreamService? = null
        const val ACTION_STOP = "cam.lenslink.android.STOP"
        private const val CHANNEL = "lenslink"
        private const val TAG = "LensLink.svc"
    }

    lateinit var engine: CameraEngine
        private set
    private lateinit var server: StreamServer
    private val main = Handler(Looper.getMainLooper())

    @Volatile var armed = false
    @Volatile var paused = false
    @Volatile var tallyProgram = false
    @Volatile var tallyPreview = false
    @Volatile var obsHost = ""
    @Volatile var lastError: String? = null
    @Volatile private var curW = 0
    @Volatile private var curH = 0
    val framesSent = AtomicLong()
    val bytesSent = AtomicLong()

    val clientConnected: Boolean get() = server.isConnected

    private var nsdListener: NsdManager.RegistrationListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null

    private val deviceName: String by lazy {
        Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        engine = CameraEngine(this, this)
        server = StreamServer(this)
        val p = getSharedPreferences("lenslink", Context.MODE_PRIVATE)
        engine.resolution = p.getString("res", "1080p") ?: "1080p"
        engine.fps = p.getInt("fps", 60)
        engine.codec = p.getString("codec", "hevc") ?: "hevc"
        engine.quality = p.getString("quality", "balanced") ?: "balanced"
        armed = p.getBoolean("armed", false)
        engine.setLook(p.getString("look", "natural") ?: "natural")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        acquireLocks()
        server.start()
        registerNsd()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        try { nsdListener?.let { (getSystemService(NSD_SERVICE) as NsdManager).unregisterService(it) } } catch (_: Exception) {}
        server.stop()
        engine.release()
        try { multicast?.release() } catch (_: Exception) {}
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "LensLink", NotificationManager.IMPORTANCE_LOW))
        val stop = android.app.PendingIntent.getService(
            this, 0, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("LensLink Android")
            .setContentText("Servidor activo en el puerto ${Proto.PORT}")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(Notification.Action.Builder(0, "Detener", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(1, n)
    }

    private fun acquireLocks() {
        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicast = wifi.createMulticastLock("lenslink").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { Log.w(TAG, "locks: ${e.message}") }
    }

    /** El wake lock de CPU solo se mantiene mientras se emite: en espera el móvil puede dormir la CPU. */
    private fun wakeOn() {
        try {
            if (wake?.isHeld == true) return
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lenslink:stream").apply { acquire() }
        } catch (e: Exception) { Log.w(TAG, "wake: ${e.message}") }
    }

    private fun wakeOff() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
    }

    /** Anuncia _lenslink._tcp para que el plugin de OBS lo descubra por nombre. */
    private fun registerNsd() {
        try {
            val nsd = getSystemService(NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = "_lenslink._tcp."
                port = Proto.PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "NSD ok: ${i.serviceName}") }
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "NSD fail $code") }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            }
            nsdListener = l
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) { Log.w(TAG, "NSD: ${e.message}") }
    }

    // ------------------------------------------------------------------ acciones (UI / remoto)

    fun startCamera() { lastError = null; paused = false; engine.start() }

    /** `disarm` = parada hecha en el teléfono (también desarma el inicio remoto). */
    fun stopCamera(disarm: Boolean) {
        if (disarm) changeArmed(false)
        engine.stop()
    }

    fun changeArmed(v: Boolean) {
        armed = v
        getSharedPreferences("lenslink", Context.MODE_PRIVATE).edit().putBoolean("armed", v).apply()
        if (!engine.running) sendHello() // el plugin reacciona al HELLO con armed:true/false
    }

    // ------------------------------------------------------------------ envío

    private fun helloJson(): JSONObject {
        val j = JSONObject()
            .put("name", deviceName).put("app", "LensLink")
            .put("protocol", Proto.VERSION).put("kind", "camera")
        if (!engine.running) { j.put("standby", true); j.put("armed", armed) }
        return j
    }

    private fun sendHello() = server.sendJson(Proto.HELLO, helloJson())

    private fun sendConfig() {
        server.sendJson(Proto.VIDEO_CONFIG, JSONObject()
            .put("codec", engine.codec).put("width", curW).put("height", curH)
            .put("fps", engine.fps).put("kind", "camera"))
    }

    private fun sendState() = server.sendJson(Proto.STATE, engine.stateJson(paused))

    private val stateRunnable = Runnable { Thread { sendState() }.start() }

    // ------------------------------------------------------------------ StreamServer.Host

    override fun clockNow() = engine.clockNow()

    override fun onConnected() {
        sendHello()
        if (engine.running) { sendConfig(); engine.requestKeyframe() }
        sendState()
    }

    override fun onDisconnected() {
        tallyProgram = false; tallyPreview = false; obsHost = ""
    }

    override fun onNeedKeyframe() = engine.requestKeyframe()
    override fun onBackpressure() = engine.onBackpressure()

    override fun onControl(cmd: JSONObject) {
        when (cmd.optString("cmd")) {
            "start_stream" -> if (armed && !engine.running) startCamera() else if (!armed) sendHello()
            "stop_stream" -> stopCamera(disarm = false)
            "pause_stream" -> if (engine.running) { paused = true; sendState() }
            "resume_stream" -> if (paused) { paused = false; engine.requestKeyframe(); sendState() }
            "set_format" -> engine.setFormat(
                cmd.optString("resolution").ifEmpty { null },
                if (cmd.has("fps")) cmd.optInt("fps") else null,
                cmd.optString("codec").ifEmpty { null })
            "set_quality" -> engine.changeQuality(cmd.optString("quality"))
            "tally" -> { tallyProgram = cmd.optBoolean("program"); tallyPreview = cmd.optBoolean("preview") }
            "identify" -> obsHost = cmd.optString("host")
            else -> engine.handleControl(cmd)
        }
    }

    // ------------------------------------------------------------------ CameraEngine.Callback

    override fun onStarted(width: Int, height: Int) {
        curW = width; curH = height
        wakeOn()
        sendHello()   // ya sin "standby"
        sendConfig()
        sendState()
        engine.requestKeyframe()
    }

    override fun onVideo(data: ByteArray, key: Boolean, ptsNs: Long) {
        if (paused) return
        server.sendVideo(data, key, ptsNs)
        framesSent.incrementAndGet()
        bytesSent.addAndGet(data.size.toLong())
    }

    override fun onStateChanged() {
        main.removeCallbacks(stateRunnable)
        main.postDelayed(stateRunnable, 200) // debounce ~200 ms como la app de iPhone
    }

    override fun onStopped(reason: String?) {
        if (reason != null) lastError = reason
        wakeOff()
        paused = false
        sendHello()  // vuelve a standby
        sendState()
    }
}
