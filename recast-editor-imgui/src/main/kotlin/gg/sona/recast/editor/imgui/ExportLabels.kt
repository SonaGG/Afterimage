package gg.sona.recast.editor.imgui

import gg.sona.recast.clip.export.ExportHandle
import gg.sona.recast.core.time.Nanos
import gg.sona.recast.editor.look.LookSettings
import gg.sona.recast.render.ExportEncoding
import gg.sona.recast.render.ExportFormat
import gg.sona.recast.render.ExportLive
import gg.sona.recast.render.ExportSettings
import gg.sona.recast.render.QualityMode
import java.util.*

object ExportLabels {

    fun output(settings: ExportSettings): List<String> =
        listOf("${settings.width} × ${settings.height}", "${settings.fps} fps", settings.format.label)

    fun quality(settings: ExportSettings): List<String> {
        val parts = ArrayList<String>(5)
        when {
            settings.format == ExportFormat.MOV_PRORES -> parts += "ProRes " + (PRORES_NAMES.getOrNull(settings.proresProfile) ?: settings.proresProfile.toString())
            settings.format == ExportFormat.GIF -> parts += "${settings.gifColors} colours"
            settings.format == ExportFormat.JPEG_SEQUENCE -> parts += "quality ${settings.jpegQuality}"
            settings.format.supportsBitrate -> parts += if (settings.qualityMode == QualityMode.CRF) "CRF ${ExportEncoding.crf(settings)}" else String.format("%.1f Mbps", settings.bitrateKbps / 1000.0)
        }
        if (settings.pixelFormat.startsWith("yuv444")) parts += "4:4:4"
        if (settings.supersampleFactor > 1) parts += "${settings.supersampleFactor}× supersampling"
        if (settings.motionBlur.enabled) parts += "${settings.motionBlur.sampleCount}× motion blur"
        if (settings.gameAudio) parts += "game audio"
        if (settings.transparent) parts += "transparent"
        if (settings.depthMap && ExportEncoding.supportsDepth(settings.format)) parts += "depth map"
        if (settings.lookActive) parts += "look"
        return parts
    }

    fun look(look: LookSettings): List<String> {
        val parts = ArrayList<String>(6)
        if (look.depthOfField) parts += "depth of field"
        if (look.exposure != 0.0 || look.contrast != 1.0 || look.saturation != 1.0) parts += "grade"
        if (look.lut.isNotEmpty()) parts += look.lut.removeSuffix(".cube").removeSuffix(".CUBE")
        if (look.vignette > 0.0) parts += "vignette"
        if (look.letterbox > 0.0) parts += String.format(Locale.ROOT, "letterbox %.2f", look.letterbox)
        if (look.grain > 0.0) parts += "grain"
        return parts
    }

    fun length(settings: ExportSettings): List<String> = listOf(
        TimeFormat.short(settings.outputDurationNanos),
        "${settings.frameCount} frames",
        "~" + size(ExportEncoding.estimateBytes(settings)),
    )

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format("%.0f MB", bytes / 1048576.0)
        else -> String.format("%.0f kB", bytes / 1024.0)
    }

    fun clock(nanos: Long): String {
        val total = nanos / Nanos.PER_SECOND
        val hours = total / 3600
        val minutes = (total / 60) % 60
        val seconds = total % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format("%d:%02d", minutes, seconds)
    }

    private val PRORES_NAMES = listOf("Proxy", "LT", "Standard", "HQ", "4444", "4444 XQ")
}

class ExportStats {
    private var smoothedFps = 0.0
    private var lastFrames = -1L
    private var lastSampleNanos = 0L
    private var latchedRemainingNanos = -1L
    private var latchedAtNanos = 0L
    private var tracked: UUID? = null

    class Sample(val framesDone: Long, val frameCount: Long, val fps: Double, val elapsedNanos: Long, val remainingNanos: Long) {
        val fraction: Float get() = if (frameCount > 0) (framesDone.toFloat() / frameCount).coerceIn(0f, 1f) else 0f

        val frames: String
            get() = if (frameCount > 0) String.format("Frame %,d of %,d", framesDone, frameCount) else String.format("Frame %,d", framesDone)

        val remaining: String
            get() {
                if (remainingNanos < 0L) return "Estimating time remaining"
                val seconds = remainingNanos / Nanos.PER_SECOND
                val minutes = (seconds + 30) / 60
                return when {
                    seconds < 5 -> "Almost done"
                    seconds < 60 -> "Less than a minute remaining"
                    seconds < 90 -> "About a minute remaining"
                    minutes < 60 -> "About $minutes minutes remaining"
                    minutes % 60 == 0L -> "About ${minutes / 60} hour${if (minutes == 60L) "" else "s"} remaining"
                    else -> "About ${minutes / 60} h ${minutes % 60} min remaining"
                }
            }

        fun chips(): List<String> {
            val parts = ArrayList<String>(4)
            parts += frames.replaceFirstChar { it.lowercase() }
            if (fps > 0.0) parts += String.format("%.1f fps", fps)
            parts += "${ExportLabels.clock(elapsedNanos)} elapsed"
            if (remainingNanos >= 0L) parts += "${ExportLabels.clock(remainingNanos)} left"
            return parts
        }
    }

    fun sample(nowNanos: Long, live: ExportLive?, handle: ExportHandle?): Sample {
        if (handle?.id != tracked) {
            tracked = handle?.id
            smoothedFps = 0.0
            lastFrames = -1L
            lastSampleNanos = 0L
            latchedRemainingNanos = -1L
            latchedAtNanos = 0L
        }
        val framesDone = live?.framesDone ?: 0L
        val frameCount = live?.frameCount ?: 0L
        if (live != null && framesDone != lastFrames) {
            if (lastFrames >= 0 && nowNanos > lastSampleNanos) {
                val instant = (framesDone - lastFrames) * Nanos.PER_SECOND.toDouble() / (nowNanos - lastSampleNanos)
                smoothedFps = if (smoothedFps == 0.0) instant else smoothedFps + (instant - smoothedFps) * 0.2
            }
            lastFrames = framesDone
            lastSampleNanos = nowNanos
        }
        val started = handle?.startedAtNanos?.takeIf { it > 0L } ?: live?.startedAtNanos ?: nowNanos
        val elapsed = (nowNanos - started).coerceAtLeast(0L)
        val averageFps = if (elapsed >= WARMUP_NANOS && framesDone > 0L) framesDone * Nanos.PER_SECOND.toDouble() / elapsed else 0.0
        val estimate = if (averageFps > 0.0 && frameCount > framesDone) ((frameCount - framesDone) / averageFps * Nanos.PER_SECOND).toLong() else -1L
        if (estimate < 0L) {
            latchedRemainingNanos = -1L
        } else if (latchedRemainingNanos < 0L || nowNanos - latchedAtNanos >= REFRESH_NANOS) {
            latchedRemainingNanos = estimate
            latchedAtNanos = nowNanos
        }
        val remaining = if (latchedRemainingNanos < 0L) -1L else (latchedRemainingNanos - (nowNanos - latchedAtNanos)).coerceAtLeast(0L)
        return Sample(framesDone, frameCount, smoothedFps, elapsed, remaining)
    }

    fun chips(nowNanos: Long, live: ExportLive?, handle: ExportHandle?): List<String> = sample(nowNanos, live, handle).chips()

    private companion object {
        const val WARMUP_NANOS = 3L * Nanos.PER_SECOND
        const val REFRESH_NANOS = 5L * Nanos.PER_SECOND
    }
}
