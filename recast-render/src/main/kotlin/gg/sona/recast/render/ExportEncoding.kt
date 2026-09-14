package gg.sona.recast.render

import gg.sona.recast.render.ffmpeg.VideoColor
import java.nio.file.Path
import java.util.*

object ExportEncoding {

    fun resolveCodec(settings: ExportSettings, encoders: Set<String>): String {
        val requested = settings.videoCodec
        if (requested.isNotBlank() && requested != "auto") return requested
        if (!settings.hardware || settings.pixelFormat.startsWith("yuv444")) return settings.format.codec
        return when (settings.format) {
            ExportFormat.MP4_H264 -> HARDWARE_H264.firstOrNull { it in encoders } ?: "libx264"
            ExportFormat.MP4_H265 -> HARDWARE_H265.firstOrNull { it in encoders } ?: "libx265"
            else -> settings.format.codec
        }
    }

    fun requestedPixelFormat(settings: ExportSettings): String = when {
        settings.transparent && settings.format == ExportFormat.MOV_PRORES -> "yuva444p10le"
        settings.transparent && settings.format == ExportFormat.WEBM_VP9 -> "yuva420p"
        settings.format != ExportFormat.MOV_PRORES -> settings.pixelFormat
        settings.proresProfile >= PRORES_4444 -> "yuv444p10le"
        else -> "yuv422p10le"
    }

    fun supportsAlpha(format: ExportFormat, proresProfile: Int): Boolean = when (format) {
        ExportFormat.PNG_SEQUENCE, ExportFormat.WEBM_VP9 -> true
        ExportFormat.MOV_PRORES -> proresProfile >= PRORES_4444
        else -> false
    }

    fun supportsDepth(format: ExportFormat): Boolean = format != ExportFormat.GIF && format != ExportFormat.JPEG_SEQUENCE

    fun depthOutput(output: Path): Path {
        val name = output.fileName.toString()
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        return output.resolveSibling("$stem-depth$extension")
    }

    fun depthPixelFormat(settings: ExportSettings, codec: String): String = when {
        settings.format == ExportFormat.MOV_PRORES -> if (settings.proresProfile >= PRORES_4444) "yuv444p10le" else "yuv422p10le"
        isHardware(codec) -> "yuv420p"
        else -> "yuv420p10le"
    }

    fun depthVideoFilters(settings: ExportSettings, outputPixelFormat: String): String {
        val color = videoColor(settings) ?: return "format=$outputPixelFormat"
        return "scale=in_range=pc:out_range=${color.filterRange}:out_color_matrix=${color.filterMatrix},format=$outputPixelFormat"
    }

    fun isHardware(codec: String): Boolean =
        codec.endsWith("_nvenc") || codec.endsWith("_amf") || codec.endsWith("_qsv") || codec.endsWith("_videotoolbox")

    fun crf(settings: ExportSettings): Int = settings.crf.coerceIn(MIN_CRF, MAX_CRF)

    fun videoColor(settings: ExportSettings): VideoColor? =
        if (settings.format == ExportFormat.GIF) null else VideoColor.BT709_LIMITED

    fun videoFilters(settings: ExportSettings, outputPixelFormat: String?): String {
        val filters = fadeFilters(settings).toMutableList()
        if (settings.format == ExportFormat.GIF) {
            filters += "split[s0][s1];[s0]palettegen=max_colors=${settings.gifColors.coerceIn(2, 256)}:stats_mode=diff[p];[s1][p]paletteuse=dither=${settings.gifDither}"
        } else if (outputPixelFormat != null) {
            videoColor(settings)?.let {
                filters += "scale=in_range=pc:out_range=${it.filterRange}:out_color_matrix=${it.filterMatrix}"
            }
            filters += "format=$outputPixelFormat"
        }
        return if (filters.isEmpty()) "null" else filters.joinToString(",")
    }

    fun fadeFilters(settings: ExportSettings): List<String> {
        val result = ArrayList<String>()
        val seconds = settings.outputDurationNanos / 1_000_000_000.0
        if (settings.fadeInSeconds > 0.0) result += String.format(
            Locale.ROOT,
            "fade=t=in:st=0:d=%.3f",
            settings.fadeInSeconds
        )
        if (settings.fadeOutSeconds > 0.0 && seconds > settings.fadeOutSeconds) result += String.format(
            Locale.ROOT,
            "fade=t=out:st=%.3f:d=%.3f",
            seconds - settings.fadeOutSeconds,
            settings.fadeOutSeconds
        )
        return result
    }

    fun codecOptions(settings: ExportSettings, codec: String): Map<String, String> {
        val options = LinkedHashMap<String, String>()
        when (settings.format) {
            ExportFormat.GIF, ExportFormat.PNG_SEQUENCE, ExportFormat.JPEG_SEQUENCE -> Unit
            ExportFormat.MOV_PRORES -> {
                options["profile"] = settings.proresProfile.toString()
                options["vendor"] = "apl0"
            }

            else -> {
                options += qualityOptions(settings, codec)
                if (codec == "libvpx-vp9") {
                    options["row-mt"] = "1"
                    options["deadline"] = "good"
                    options["cpu-used"] = vp9Speed(settings.preset)
                }
            }
        }
        return options
    }

    fun containerOptions(settings: ExportSettings): Map<String, String> = when (settings.format) {
        ExportFormat.MP4_H264, ExportFormat.MP4_H265 -> mapOf("movflags" to "+faststart")
        ExportFormat.GIF -> mapOf("loop" to "0")
        else -> emptyMap()
    }

    fun videoTag(settings: ExportSettings): String? = if (settings.format == ExportFormat.MP4_H265) "hvc1" else null

    fun qualityOptions(settings: ExportSettings, codec: String): Map<String, String> {
        val hardware = codec.endsWith("_nvenc") || codec.endsWith("_amf") || codec.endsWith("_qsv")
        if (settings.qualityMode == QualityMode.BITRATE && settings.bitrateKbps > 0) {
            val kbps = settings.bitrateKbps
            val base = linkedMapOf("b" to "${kbps}k", "maxrate" to "${(kbps * 1.5).toInt()}k", "bufsize" to "${kbps * 2}k")
            if (!hardware) base["preset"] = settings.preset
            return base
        }
        val crf = crf(settings).toString()
        return when {
            codec.endsWith("_nvenc") -> linkedMapOf(
                "rc" to "vbr",
                "cq" to crf,
                "b" to "0",
                "preset" to nvencPreset(settings.preset),
            )

            codec.endsWith("_amf") -> linkedMapOf(
                "rc" to "cqp",
                "qp_i" to crf,
                "qp_p" to crf,
            )

            codec.endsWith("_qsv") -> linkedMapOf("global_quality" to crf, "preset" to settings.preset)
            codec == "libvpx-vp9" -> linkedMapOf("crf" to crf, "b" to "0")
            else -> linkedMapOf("crf" to crf, "preset" to settings.preset)
        }
    }

    private fun nvencPreset(preset: String): String = when (preset) {
        "fast", "veryfast", "ultrafast" -> "p3"
        "slow", "slower", "veryslow" -> "p6"
        else -> "p5"
    }

    private fun vp9Speed(preset: String): String = when (preset) {
        "fast", "veryfast", "ultrafast" -> "4"
        "slow", "slower", "veryslow" -> "1"
        else -> "2"
    }

    fun audioCodec(format: ExportFormat): String = when (format) {
        ExportFormat.WEBM_VP9 -> "libopus"
        ExportFormat.MOV_PRORES -> "pcm_s16le"
        else -> "aac"
    }

    fun audioBitrate(format: ExportFormat): Long = when (format) {
        ExportFormat.WEBM_VP9 -> 160_000L
        ExportFormat.MOV_PRORES -> 0L
        else -> 192_000L
    }

    fun applyAudioFades(mix: AudioMix, settings: ExportSettings) {
        val seconds = settings.outputDurationNanos / 1_000_000_000.0
        if (settings.fadeInSeconds > 0.0) {
            val length = (settings.fadeInSeconds * mix.sampleRate).toInt().coerceIn(1, mix.frames)
            for (i in 0 until length) {
                val gain = i.toFloat() / length
                mix.left[i] *= gain
                mix.right[i] *= gain
            }
        }
        if (settings.fadeOutSeconds > 0.0 && seconds > settings.fadeOutSeconds) {
            val length = (settings.fadeOutSeconds * mix.sampleRate).toInt().coerceIn(1, mix.frames)
            val start = mix.frames - length
            for (i in 0 until length) {
                val gain = 1f - i.toFloat() / length
                mix.left[start + i] *= gain
                mix.right[start + i] *= gain
            }
        }
    }

    fun estimateBytes(settings: ExportSettings): Long {
        val seconds = settings.outputDurationNanos / 1_000_000_000.0
        if (settings.qualityMode == QualityMode.BITRATE && settings.bitrateKbps > 0 && settings.format.supportsBitrate) {
            return (settings.bitrateKbps * 1000.0 / 8.0 * seconds).toLong()
        }
        val pixelsPerSecond = settings.width.toDouble() * settings.height * settings.fps
        val bitsPerPixel = when (settings.format) {
            ExportFormat.MP4_H264 -> 0.12 * Math.pow(1.13, (18 - settings.crf).toDouble())
            ExportFormat.MP4_H265 -> 0.07 * Math.pow(1.13, (22 - settings.crf).toDouble())
            ExportFormat.WEBM_VP9 -> 0.08 * Math.pow(1.13, (28 - settings.crf).toDouble())
            ExportFormat.MOV_PRORES -> 3.2
            ExportFormat.GIF -> 1.5
            ExportFormat.PNG_SEQUENCE -> 8.0
            ExportFormat.JPEG_SEQUENCE -> 1.2
        }
        return (pixelsPerSecond * bitsPerPixel / 8.0 * seconds).toLong()
    }

    fun bitrateForSize(targetBytes: Long, settings: ExportSettings, audioKbps: Int = 192): Int {
        val seconds = settings.outputDurationNanos / 1_000_000_000.0
        if (seconds <= 0.0) return 0
        val totalKbps = targetBytes * 8.0 / 1000.0 / seconds
        return (totalKbps - (if (settings.format.supportsAudio && settings.audioFile != null) audioKbps else 0)).toInt()
            .coerceAtLeast(100)
    }

    const val MIN_CRF = 1
    const val MAX_CRF = 51
    const val PRORES_4444 = 4

    private val HARDWARE_H264 = listOf("h264_nvenc", "h264_amf", "h264_qsv", "h264_videotoolbox")
    private val HARDWARE_H265 = listOf("hevc_nvenc", "hevc_amf", "hevc_qsv", "hevc_videotoolbox")
}
