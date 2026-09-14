package gg.sona.afterimage.render.ffmpeg

import org.bytedeco.ffmpeg.avutil.LogCallback
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import java.util.ArrayDeque

object FfmpegLog {
    private const val KEEP = 16
    private val lines = ArrayDeque<String>()
    private val partial = StringBuilder()

    private var callback: LogCallback? = null

    fun install() {
        if (callback != null) return
        val installed = object : LogCallback() {
            override fun call(level: Int, message: BytePointer) {
                if (level > avutil.AV_LOG_WARNING) return
                append(message.string)
            }
        }.retainReference<LogCallback>()
        callback = installed
        avutil.av_log_set_level(avutil.AV_LOG_WARNING)
        avutil.setLogCallback(installed)
    }

    @Synchronized
    private fun append(text: String) {
        partial.append(text)
        while (true) {
            val newline = partial.indexOf("\n")
            if (newline < 0) break
            val line = partial.substring(0, newline).trim()
            partial.delete(0, newline + 1)
            if (line.isEmpty()) continue
            if (lines.size == KEEP) lines.removeFirst()
            lines.addLast(line)
        }
    }

    @Synchronized
    fun recent(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        partial.setLength(0)
    }
}
