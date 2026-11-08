package cam.lenslink.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro corto de eventos (últimos 60) para el informe de diagnóstico. */
object DiagLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > 60) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
