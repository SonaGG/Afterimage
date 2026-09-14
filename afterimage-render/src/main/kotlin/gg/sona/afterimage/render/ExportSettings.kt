package gg.sona.afterimage.render

import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.look.LookSettings
import java.nio.file.Path

data class ExportSettings(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val startNanos: Long,
    val endNanos: Long,
    val output: Path,
    val videoCodec: String = "auto",
    val crf: Int = 18,
    val preset: String = "medium",
    val pixelFormat: String = "yuv420p",
    val includeAudio: Boolean = false,
    val supersample: Int = 1,
    val depthMap: Boolean = false,
    val projection: ExportProjection = ExportProjection.PERSPECTIVE,
    val stereoSeparation: Double = 0.065,
    val orthoScale: Float = 10f,
    val timeMapping: TimeMapping? = null,
    val audioFile: Path? = null,
    val audioOffsetSeconds: Double = 0.0,
    val audioVolume: Double = 1.0,
    val format: ExportFormat = ExportFormat.MP4_H264,
    val qualityMode: QualityMode = QualityMode.CRF,
    val bitrateKbps: Int = 0,
    val hardware: Boolean = false,
    val motionBlur: MotionBlur = MotionBlur.OFF,
    val gifColors: Int = 256,
    val gifDither: String = "sierra2_4a",
    val proresProfile: Int = 3,
    val jpegQuality: Int = 92,
    val fadeInSeconds: Double = 0.0,
    val fadeOutSeconds: Double = 0.0,
    val waitForChunks: Boolean = true,
    val gameAudio: Boolean = true,
    val gameAudioVolume: Double = 1.0,
    val look: LookSettings? = null,
    val alpha: Boolean = false,
    val depthRange: Double = 0.0,
) {
    val lookActive: Boolean get() = look?.active == true && projection != ExportProjection.EQUIRECTANGULAR

    val depthOfField: Boolean get() = lookActive && look?.depthOfField == true

    val transparent: Boolean get() = alpha && ExportEncoding.supportsAlpha(format, proresProfile)

    val frameIntervalNanos: Long get() = Nanos.PER_SECOND / fps

    val outputDurationNanos: Long get() = timeMapping?.outputDurationNanos ?: maxOf(0L, endNanos - startNanos)

    val frameCount: Long get() = outputDurationNanos / frameIntervalNanos + 1

    val supersampleFactor: Int
        get() = when {
            supersample >= 4 -> 4
            supersample >= 2 -> 2
            else -> 1
        }

    fun replayNanosAt(outputNanos: Long): Long = timeMapping?.replayNanosAt(outputNanos) ?: (startNanos + outputNanos)
}
