package gg.sona.recast.render

import java.util.*

object FfmpegCommand {

    fun build(
        executable: String,
        settings: ExportSettings,
        audio: AudioSource?,
        encoders: Set<String> = emptySet()
    ): List<String> {
        val command = ArrayList<String>()
        command += executable
        command += listOf(
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "rawvideo",
            "-pix_fmt",
            "rgba",
            "-s",
            "${settings.width}x${settings.height}",
            "-r",
            settings.fps.toString(),
            "-i",
            "-"
        )
        val withAudio = audio != null && settings.format.supportsAudio
        if (withAudio) command += audio!!.ffmpegInputArguments()
        val codec = resolveCodec(settings, encoders)
        val fades = fadeFilters(settings)
        when (settings.format) {
            ExportFormat.GIF -> {
                val palette = "split[s0][s1];[s0]palettegen=max_colors=${
                    settings.gifColors.coerceIn(
                        2,
                        256
                    )
                }:stats_mode=diff[p];[s1][p]paletteuse=dither=${settings.gifDither}"
                command += listOf("-vf", (fades + palette).joinToString(","), "-loop", "0")
            }

            ExportFormat.MOV_PRORES -> command += listOf(
                "-c:v",
                "prores_ks",
                "-profile:v",
                settings.proresProfile.toString(),
                "-pix_fmt",
                "yuv422p10le",
                "-vendor",
                "apl0"
            )

            ExportFormat.PNG_SEQUENCE, ExportFormat.JPEG_SEQUENCE -> Unit

            else -> {
                if (fades.isNotEmpty()) command += listOf("-vf", fades.joinToString(","))
                command += listOf("-c:v", codec)
                command += qualityArguments(settings, codec)
                command += listOf("-pix_fmt", settings.pixelFormat)
                if (settings.format == ExportFormat.MP4_H264 || settings.format == ExportFormat.MP4_H265) command += listOf(
                    "-movflags",
                    "+faststart"
                )
                if (settings.format == ExportFormat.MP4_H265) command += listOf("-tag:v", "hvc1")
                if (codec == "libvpx-vp9") command += listOf(
                    "-row-mt",
                    "1",
                    "-deadline",
                    "good",
                    "-cpu-used",
                    vp9Speed(settings.preset)
                )
            }
        }
        if (withAudio) {
            command += if (settings.format == ExportFormat.WEBM_VP9) listOf(
                "-c:a",
                "libopus",
                "-b:a",
                "160k"
            ) else listOf("-c:a", "aac", "-b:a", "192k")
            val audioFades = audioFadeFilters(settings)
            if (audioFades.isNotEmpty()) command += listOf("-af", audioFades.joinToString(","))
        }
        command += settings.extraArguments
        command += settings.output.toString()
        return command
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

    fun audioFadeFilters(settings: ExportSettings): List<String> {
        val result = ArrayList<String>()
        val seconds = settings.outputDurationNanos / 1_000_000_000.0
        if (settings.fadeInSeconds > 0.0) result += String.format(
            Locale.ROOT,
            "afade=t=in:st=0:d=%.3f",
            settings.fadeInSeconds
        )
        if (settings.fadeOutSeconds > 0.0 && seconds > settings.fadeOutSeconds) result += String.format(
            Locale.ROOT,
            "afade=t=out:st=%.3f:d=%.3f",
            seconds - settings.fadeOutSeconds,
            settings.fadeOutSeconds
        )
        return result
    }

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

    fun qualityArguments(settings: ExportSettings, codec: String): List<String> {
        val hardware = codec.endsWith("_nvenc") || codec.endsWith("_amf") || codec.endsWith("_qsv")
        if (settings.qualityMode == QualityMode.BITRATE && settings.bitrateKbps > 0) {
            val kbps = settings.bitrateKbps
            val base = listOf("-b:v", "${kbps}k", "-maxrate", "${(kbps * 1.5).toInt()}k", "-bufsize", "${kbps * 2}k")
            return if (hardware) base else base + listOf("-preset", settings.preset)
        }
        return when {
            codec.endsWith("_nvenc") -> listOf(
                "-rc",
                "vbr",
                "-cq",
                settings.crf.toString(),
                "-b:v",
                "0",
                "-preset",
                nvencPreset(settings.preset)
            )

            codec.endsWith("_amf") -> listOf(
                "-rc",
                "cqp",
                "-qp_i",
                settings.crf.toString(),
                "-qp_p",
                settings.crf.toString()
            )

            codec.endsWith("_qsv") -> listOf("-global_quality", settings.crf.toString(), "-preset", settings.preset)
            codec == "libvpx-vp9" -> listOf("-crf", settings.crf.toString(), "-b:v", "0")
            else -> listOf("-crf", settings.crf.toString(), "-preset", settings.preset)
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

    fun parseEncoders(output: String): Set<String> {
        val result = LinkedHashSet<String>()
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.length < 8 || trimmed[0] != 'V') continue
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size >= 2 && parts[0].length == 6 && parts[1] != "=") result += parts[1]
        }
        return result
    }

    private val HARDWARE_H264 = listOf("h264_nvenc", "h264_amf", "h264_qsv", "h264_videotoolbox")
    private val HARDWARE_H265 = listOf("hevc_nvenc", "hevc_amf", "hevc_qsv", "hevc_videotoolbox")
}
