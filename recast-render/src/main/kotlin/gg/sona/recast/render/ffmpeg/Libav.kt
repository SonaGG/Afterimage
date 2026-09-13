package gg.sona.recast.render.ffmpeg

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
        return if (avutil.av_strerror(code, buffer, 256L) == 0) buffer.string else "error $code"
    }

    fun context(): String {
        val recent = FfmpegLog.recent()
        return if (recent.isEmpty()) "" else " (${recent.takeLast(3).joinToString(" | ")})"
    }

    fun stereo(): AVChannelLayout = AVChannelLayout().also { avutil.av_channel_layout_default(it, 2) }
}
