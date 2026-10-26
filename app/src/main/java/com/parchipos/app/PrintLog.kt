package com.parchipos.app

import android.content.Context
import java.io.File

/**
 * Rolling print log, kept across restarts.
 *
 * The question worth asking it — why did the parchi not come out at three
 * o'clock — has to survive the app being closed, so lines are appended to a
 * small file and reloaded on start.
 */
object PrintLog {
    private const val MAX_LINES = 120
    private const val FILE = "print.log"

    private val lines = ArrayDeque<String>()
    private var file: File? = null

    /** Call once, early. Safe to call again. */
    @Synchronized fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE)
        file = f
        try {
            if (f.exists()) {
                f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
            }
        } catch (_: Exception) { /* a missing history is not worth failing over */ }
    }

    @Synchronized fun append(line: String) {
        val stamp = android.text.format.DateFormat.format("dd MMM HH:mm:ss", System.currentTimeMillis())
        val entry = "$stamp  $line"
        lines.addLast(entry)
        while (lines.size > MAX_LINES) lines.removeFirst()
        persist(entry)
    }

    @Synchronized fun text(): String = lines.reversed().joinToString("\n")

    @Synchronized fun clear() {
        lines.clear()
        try { file?.writeText("") } catch (_: Exception) {}
    }

    private fun persist(entry: String) {
        val f = file ?: return
        try {
            f.appendText(entry + "\n")
            // Rewrite rather than grow without bound; cheap at this size.
            if (f.length() > 64 * 1024) {
                f.writeText(lines.joinToString("\n", postfix = "\n"))
            }
        } catch (_: Exception) { /* logging must never break printing */ }
    }
}
