package gg.sona.recast.render

import gg.sona.recast.camera.CameraPose
import gg.sona.recast.camera.PoseSource
import gg.sona.recast.clip.export.ExportProgress
import gg.sona.recast.core.log.RecastLog
import gg.sona.recast.render.sink.FrameSink
import java.nio.file.Path

class ExportPipeline(
    private val settings: ExportSettings,
    private val replay: ReplayDriver,
    private val camera: PoseSource,
    private val frames: FrameSource,
    private val sink: FrameSink,
    private val renderThread: MainThreadExecutor = DirectExecutor,
) {

    private val logger = RecastLog.logger("recast.render")

    @Volatile
    var cancelled: Boolean = false

    @Volatile
    var status: String = ""
        private set

    @Volatile
    var framesDone: Long = 0L
        private set

    val frameCount: Long get() = settings.frameCount

    fun run(progress: (Double) -> Unit = {}): Path = run(ExportProgress(progress) {})

    fun run(report: ExportProgress): Path {
        val frameCount = settings.frameCount
        val pixels = settings.width * settings.height
        val buffer = ByteArray(pixels * 4)
        val depth = if (settings.depthMap) FloatArray(pixels) else null
        val passes = settings.projection.passes
        val samples = settings.motionBlur.sampleCount
        val startedAt = System.nanoTime()
        sink.begin(settings)
        var primary: Throwable? = null
        try {
            renderThread.call { frames.prepare(settings) }
            replay.pause()
            var index = 0L
            var outputNanos = 0L
            while (outputNanos <= settings.outputDurationNanos && !cancelled) {
                val frameIndex = index
                for (sample in 0 until samples) {
                    if (cancelled) break
                    val sampleNanos = sampleNanos(outputNanos, sample)
                    for (pass in 0 until passes) {
                        if (cancelled) break
                        renderThread.call {
                            if (pass == 0 && sample == 0) frames.beginFrame(frameIndex, outputNanos)
                            if (pass == 0) replay.advanceTo(sampleNanos)
                            val pose = camera.poseAt(sampleNanos) ?: CameraPose.ORIGIN
                            frames.renderPass(sampleNanos, pose, pass)
                        }
                    }
                    if (cancelled) break
                    val delivered = renderThread.call { frames.compose(buffer, depth, sample, samples) }
                    if (delivered != FrameSource.NO_FRAME) deliver(delivered, buffer, depth)
                }
                if (cancelled) break
                index++
                framesDone = index
                outputNanos += settings.frameIntervalNanos
                if (frameCount > 0) report.progress((index.toDouble() / frameCount).coerceIn(0.0, 1.0))
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0
                if (elapsed > 0.0) {
                    val fps = index / elapsed
                    val remaining = if (fps > 0.0) (frameCount - index) / fps else 0.0
                    status = String.format(
                        "frame %d of %d, %.1f fps, %s left",
                        index,
                        frameCount,
                        fps,
                        clock(remaining)
                    )
                    report.detail(status)
                }
            }
            if (!cancelled) {
                val delivered = renderThread.call { frames.flush(buffer, depth) }
                if (delivered != FrameSource.NO_FRAME) deliver(delivered, buffer, depth)
            }
            logger.info("Rendered $index frames to ${settings.output.fileName}")
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            runCatching { renderThread.call { frames.release() } }.onFailure { if (primary == null) throw it }
            frames.warnings().forEach(report.warn)
            try {
                if (cancelled || primary != null) sink.abort() else sink.close()
            } catch (error: Throwable) {
                if (primary == null) throw IllegalStateException("${error.message} (after $framesDone frames)", error)
                logger.warn("Sink close failed after an earlier error", error)
            }
        }
        report.progress(1.0)
        return settings.output
    }

    private fun deliver(index: Long, buffer: ByteArray, depth: FloatArray?) {
        val nanos = settings.replayNanosAt(index * settings.frameIntervalNanos)
        sink.accept(RenderedFrame(index, nanos, settings.width, settings.height, buffer, depth))
    }

    private fun sampleNanos(outputNanos: Long, sample: Int): Long {
        val offset = settings.motionBlur.offsetNanos(sample, settings.frameIntervalNanos)
        return settings.replayNanosAt((outputNanos + offset).coerceIn(0L, settings.outputDurationNanos))
    }

    private fun clock(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0L)
        val hours = total / 3600
        val minutes = (total / 60) % 60
        val secs = total % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, secs) else String.format(
            "%d:%02d",
            minutes,
            secs
        )
    }
}
