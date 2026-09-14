package gg.sona.afterimage.render.ffmpeg

import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.presets.avutil.AVERROR_EAGAIN
import org.bytedeco.javacpp.BytePointer

object Libav {
    const val SAMPLE_RATE = 48000

    val EAGAIN: Int by lazy { AVERROR_EAGAIN() }

    fun check(code: Int, what: String): Int {
        if (code >= 0) return code
        throw IllegalStateException("$what: ${describe(code)}${context()}")
    }

    fun describe(code: Int): String {
        val buffer = BytePointer(256L)
        if (avutil.av_strerror(code, buffer, 256L) != 0) return "error $code"
        val bytes = ByteArray(256)
        buffer.get(bytes)
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    fun context(): String {
        val recent = FfmpegLog.recent()
        return if (recent.isEmpty()) "" else " (${recent.takeLast(3).joinToString(" | ")})"
    }

    fun stereo(): AVChannelLayout = AVChannelLayout().also { avutil.av_channel_layout_default(it, 2) }
}
