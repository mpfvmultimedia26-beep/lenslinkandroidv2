package cam.lenslink.android

import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Escucha en TCP 9979 (igual que la app de iPhone) y habla el protocolo OBSC.
 * Una sola conexión a la vez: una nueva desplaza a la anterior.
 */
class StreamServer(private val host: Host) {

    interface Host {
        fun clockNow(): Long
        /** Se llama justo tras aceptar: aquí hay que mandar HELLO (y config/state si hay stream). */
        fun onConnected()
        fun onDisconnected()
        fun onControl(cmd: JSONObject)
        fun onNeedKeyframe()
        fun onBackpressure()
    }

    private class Out(val bytes: ByteArray)

    private inner class Conn(val socket: Socket) {
        val queue = ArrayBlockingQueue<Out>(300)
        @Volatile var alive = true
        @Volatile var needKey = true // un cliente nuevo solo entra en un keyframe
        fun close() {
            alive = false
            try { socket.close() } catch (_: Exception) {}
        }
    }

    @Volatile private var running = false
    private var server: ServerSocket? = null
    @Volatile private var conn: Conn? = null
    private var pinger = Executors.newSingleThreadScheduledExecutor()

    val isConnected: Boolean get() = conn?.alive == true

    fun start() {
        if (running) return
        running = true
        pinger.scheduleWithFixedDelay({
            send(Proto.PING, ByteArray(0))
        }, 2, 2, TimeUnit.SECONDS)

        thread(name = "lenslink-accept", isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(Proto.PORT))
                server = ss
                while (running) {
                    val s = ss.accept()
                    serve(s)
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "accept loop", e)
            }
        }
    }

    fun stop() {
        running = false
        conn?.close()
        conn = null
        try { server?.close() } catch (_: Exception) {}
        server = null
        pinger.shutdownNow()
        pinger = Executors.newSingleThreadScheduledExecutor()
    }

    private fun serve(s: Socket) {
        conn?.close()
        s.tcpNoDelay = true
        s.keepAlive = true
        val c = Conn(s)
        conn = c
        thread(name = "lenslink-write", isDaemon = true) { writeLoop(c) }
        // HELLO tiene que ser el primer paquete: se encola antes de arrancar el lector.
        host.onConnected()
        thread(name = "lenslink-read", isDaemon = true) { readLoop(c) }
    }

    fun send(type: Int, payload: ByteArray, pts: Long = host.clockNow()) {
        val c = conn ?: return
        if (!c.alive) return
        c.queue.offer(Out(Proto.packet(type, payload, pts)))
    }

    fun sendJson(type: Int, json: JSONObject) = send(type, json.toString().toByteArray(Charsets.UTF_8))

    fun sendVideo(data: ByteArray, key: Boolean, ptsNs: Long) {
        val c = conn ?: return
        if (!c.alive) return
        if (c.needKey && !key) return
        // Drop-don't-queue: si el enlace no da abasto, se tira lo pendiente y se espera otro keyframe.
        if (!key && c.queue.size > 40) {
            c.queue.clear()
            c.needKey = true
            host.onBackpressure()
            host.onNeedKeyframe()
            return
        }
        val ok = c.queue.offer(Out(Proto.packet(Proto.VIDEO, data, ptsNs, if (key) Proto.FLAG_KEYFRAME else 0)))
        if (ok) {
            if (key) c.needKey = false
        } else {
            c.queue.clear()
            c.needKey = true
            host.onBackpressure()
            host.onNeedKeyframe()
        }
    }

    private fun writeLoop(c: Conn) {
        try {
            val out = c.socket.getOutputStream()
            while (c.alive) {
                val o = c.queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                out.write(o.bytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "write loop ended: ${e.message}")
        } finally {
            c.close()
        }
    }

    private fun readLoop(c: Conn) {
        try {
            val inp = DataInputStream(c.socket.getInputStream().buffered())
            val hdr = ByteArray(Proto.HEADER)
            while (c.alive) {
                inp.readFully(hdr)
                val bb = ByteBuffer.wrap(hdr)
                if (bb.int != Proto.MAGIC) break
                if ((bb.get().toInt() and 0xFF) != Proto.VERSION) break
                val type = bb.get().toInt() and 0xFF
                bb.short // flags
                val pts = bb.long
                val size = bb.int
                if (size < 0 || size > Proto.MAX_PAYLOAD) break
                val payload = ByteArray(size)
                inp.readFully(payload)
                when (type) {
                    Proto.TIMESYNC_REQ -> {
                        // pts de la respuesta = nuestro reloj (mismo dominio que los frames);
                        // payload = t1 del plugin, devuelto tal cual (8 bytes BE).
                        send(Proto.TIMESYNC_RESP, ByteBuffer.allocate(8).putLong(pts).array(), host.clockNow())
                    }
                    Proto.CONTROL -> try {
                        host.onControl(JSONObject(String(payload, Charsets.UTF_8)))
                    } catch (e: Exception) {
                        Log.w(TAG, "bad control: ${e.message}")
                    }
                    else -> { /* tipos desconocidos se ignoran */ }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "read loop ended: ${e.message}")
        } finally {
            c.close()
            if (conn === c) {
                conn = null
                host.onDisconnected()
            }
        }
    }

    companion object { private const val TAG = "LensLink.net" }
}
