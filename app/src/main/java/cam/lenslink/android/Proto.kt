package cam.lenslink.android

import java.nio.ByteBuffer

/** Wire protocol v1 (ver docs/PROTOCOL.md del repo LensLink). Todo big-endian. */
object Proto {
    const val PORT = 9979
    const val VERSION = 1
    const val HEADER = 20
    const val MAX_PAYLOAD = 16 * 1024 * 1024
    const val MAGIC = 0x4F425343 // "OBSC"

    const val HELLO = 1
    const val VIDEO_CONFIG = 2
    const val VIDEO = 3
    const val PING = 4
    const val TIMESYNC_REQ = 5
    const val TIMESYNC_RESP = 6
    const val CONTROL = 7
    const val STATE = 8

    const val FLAG_KEYFRAME = 1

    /** Cabecera de 20 bytes + payload en un solo array. */
    fun packet(type: Int, payload: ByteArray, pts: Long, flags: Int = 0): ByteArray {
        val b = ByteBuffer.allocate(HEADER + payload.size) // big-endian por defecto
        b.putInt(MAGIC)
        b.put(VERSION.toByte())
        b.put(type.toByte())
        b.putShort(flags.toShort())
        b.putLong(pts)
        b.putInt(payload.size)
        b.put(payload)
        return b.array()
    }
}
