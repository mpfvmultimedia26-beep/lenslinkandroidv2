package cam.lenslink.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
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
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var thermalListener: Any? = null
    private var started = false
    private var batteryRx: BroadcastReceiver? = null
    private var lowActive = false
    @Volatile var batteryPct = -1
        private set
    @Volatile var batteryCharging = false
        private set
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
        engine.setStabilization(p.getString("stab", "standard") ?: "standard")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (!started) {
            started = true
            DiagLog.add("servicio iniciado")
            acquireLocks()
            server.start()
            registerNsd()
            watchNetwork()
            watchThermal()
            watchBattery()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        unregisterNsd()
        try { netCb?.let { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } } catch (_: Exception) {}
        unwatchThermal()
        try { batteryRx?.let { unregisterReceiver(it) } } catch (_: Exception) {}
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

    private fun unregisterNsd() {
        try { nsdListener?.let { (getSystemService(NSD_SERVICE) as NsdManager).unregisterService(it) } } catch (_: Exception) {}
        nsdListener = null
    }

    /** Si el Wi-Fi se cae y vuelve, se vuelve a anunciar Bonjour (el plugin lo encuentra de nuevo). */
    private fun watchNetwork() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val cbk = object : ConnectivityManager.NetworkCallback() {
                private var first = true
                override fun onAvailable(network: Network) {
                    if (first) { first = false; return } // el primer aviso es solo el estado inicial
                    DiagLog.add("red disponible: se vuelve a anunciar Bonjour")
                    main.post { unregisterNsd(); registerNsd() }
                }
            }
            cm.registerDefaultNetworkCallback(cbk)
            netCb = cbk
        } catch (e: Exception) { Log.w(TAG, "network: ${e.message}") }
    }

    /** Batería: con <= 20 % sin cargar, el motor baja 4K a 1080p y a 30 fps. Se levanta con >= 25 % o al enchufar. */
    private fun watchBattery() {
        try {
            val rx = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { handleBattery(i) }
            }
            batteryRx = rx
            registerReceiver(rx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { handleBattery(it) }
        } catch (e: Exception) { Log.w(TAG, "battery: ${e.message}") }
    }

    private fun handleBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level < 0) -1 else level * 100 / maxOf(scale, 1)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val changed = pct != batteryPct || charging != batteryCharging
        batteryPct = pct
        batteryCharging = charging
        val low = if (lowActive) !(charging || pct >= 25) else (pct in 0..20 && !charging)
        if (low != lowActive) {
            lowActive = low
            DiagLog.add("batería $pct%: ${if (low) "baja, se limita el formato" else "recuperada"}")
            engine.setBatteryLow(low)
        }
        if (changed) onStateChanged()
    }

    /** Temperatura: el motor baja bitrate / fps solo cuando el sistema avisa de calor. */
    private fun watchThermal() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val l = PowerManager.OnThermalStatusChangedListener { st -> engine.setThermalLevel(st) }
            thermalListener = l
            pm.addThermalStatusListener(l)
            engine.setThermalLevel(pm.currentThermalStatus)
        } catch (e: Exception) { Log.w(TAG, "thermal: ${e.message}") }
    }

    private fun unwatchThermal() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val l = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
            (getSystemService(POWER_SERVICE) as PowerManager).removeThermalStatusListener(l)
        } catch (_: Exception) {}
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

    private fun sendState() {
        val j = engine.stateJson(paused)
        // Batería para quien quiera leerla (el plugin de OBS actual ignora estos campos).
        if (batteryPct >= 0) {
            j.put("battery", batteryPct)
            if (batteryCharging) j.put("charging", true)
            if (lowActive) j.put("lowBattery", true)
        }
        server.sendJson(Proto.STATE, j)
    }

    private val stateRunnable = Runnable { Thread { sendState() }.start() }

    // ------------------------------------------------------------------ StreamServer.Host

    override fun clockNow() = engine.clockNow()

    override fun onConnected() {
        DiagLog.add("OBS conectado")
        sendHello()
        if (engine.running) { sendConfig(); engine.requestKeyframe() }
        sendState()
    }

    override fun onDisconnected() {
        DiagLog.add("OBS desconectado")
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
        DiagLog.add("emitiendo ${width}x$height ${engine.fps}fps ${engine.codec}")
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
