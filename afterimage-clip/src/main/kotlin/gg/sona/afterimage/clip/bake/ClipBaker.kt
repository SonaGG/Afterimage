package gg.sona.afterimage.clip.bake

import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.format.AfterimageWriter
import gg.sona.afterimage.format.RecordingHeader
import gg.sona.afterimage.format.WriterOptions
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.replay.protocol.SessionMarks
import gg.sona.afterimage.replay.consumer.DeliveryMode
import gg.sona.afterimage.replay.consumer.ReplayConsumer
import gg.sona.afterimage.replay.session.ReplaySession
import gg.sona.afterimage.replay.source.FileReplaySource
import gg.sona.afterimage.replay.source.ReplaySource
import java.nio.file.Files
import java.nio.file.Path

class ClipBaker(private val writerOptions: WriterOptions = WriterOptions()) {
    fun bake(clip: Clip, output: Path, progress: (Double) -> Unit = {}): BakeResult =
        FileReplaySource.open(clip.recording).use { source -> bake(source, clip, output, progress) }

    fun bake(source: ReplaySource, clip: Clip, output: Path, progress: (Double) -> Unit = {}): BakeResult {
        Files.createDirectories(output.toAbsolutePath().parent)
        val session = ReplaySession(source)
        session.load()
        val start = clip.startNanos.coerceIn(source.startNanos, source.endNanos)
        val end = clip.endNanos.coerceIn(start, source.endNanos)
        session.seek(start)
        val interval = source.header.keyframeIntervalNanos
        val header = RecordingHeader(
            sessionId = source.header.sessionId,
            protocolVersion = source.header.protocolVersion,
            startEpochMillis = source.header.startEpochMillis + start / 1_000_000L,
            keyframeIntervalNanos = interval,
            metadata = source.header.metadata + mapOf(
                CLIP_ID to clip.id.toString(),
                CLIP_TITLE to clip.title,
                CLIP_SOURCE to clip.recording.fileName.toString(),
                CLIP_START_NANOS to start.toString(),
            ),
        )
        var packets = 0L
        var keyframes = 0
        AfterimageWriter(output, header, writerOptions).use { writer ->
            writer.writeSnapshot(0L, session.state.snapshot(start))
            keyframes++
            var lastKeyframe = 0L
            val recorder = object : ReplayConsumer {
                override fun onPacket(packet: CapturedPacket, mode: DeliveryMode) {
                    if (packet.timestampNanos <= start) return
                    val relative = packet.timestampNanos - start
                    writer.append(packet.withTimestamp(relative))
                    packets++
                    if (relative - lastKeyframe >= interval) {
                        writer.writeSnapshot(relative, session.state.snapshot(packet.timestampNanos))
                        lastKeyframe = relative
                        keyframes++
                    }
                    if (end > start) progress(((relative.toDouble() / (end - start))).coerceIn(0.0, 1.0))
                }
            }
            session.addConsumer(recorder)
            session.seek(end, linear = true)
            session.removeConsumer(recorder)
            if (end - start > lastKeyframe) {
                writer.append(session.protocol.marker(SessionMarks.RECORDING_STOPPED, clip.title, end - start))
            }
        }
        progress(1.0)
        return BakeResult(output, packets, keyframes, end - start)
    }

    companion object {
        const val CLIP_ID = "clip.id"
        const val CLIP_TITLE = "clip.title"
        const val CLIP_SOURCE = "clip.source"
        const val CLIP_START_NANOS = "clip.start"
    }
}
