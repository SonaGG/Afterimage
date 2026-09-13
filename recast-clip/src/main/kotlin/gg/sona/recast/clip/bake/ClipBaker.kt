package gg.sona.recast.clip.bake

import gg.sona.recast.clip.Clip
import gg.sona.recast.format.RecastWriter
import gg.sona.recast.format.RecordingHeader
import gg.sona.recast.format.WriterOptions
import gg.sona.recast.net.CapturedPacket
import gg.sona.recast.protocol.PacketCodec
import gg.sona.recast.protocol.SessionMark
import gg.sona.recast.replay.consumer.DeliveryMode
import gg.sona.recast.replay.consumer.ReplayConsumer
import gg.sona.recast.replay.session.ReplaySession
import gg.sona.recast.replay.source.FileReplaySource
import gg.sona.recast.replay.source.ReplaySource
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
        RecastWriter(output, header, writerOptions).use { writer ->
            writer.writeSnapshot(0L, session.shadow.snapshot(start))
            keyframes++
            var lastKeyframe = 0L
            val recorder = object : ReplayConsumer {
                override fun onPacket(packet: CapturedPacket, mode: DeliveryMode) {
                    if (packet.timestampNanos <= start) return
                    val relative = packet.timestampNanos - start
                    writer.append(packet.withTimestamp(relative))
                    packets++
                    if (relative - lastKeyframe >= interval) {
                        writer.writeSnapshot(relative, session.shadow.snapshot(packet.timestampNanos))
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
                writer.append(PacketCodec.encode(SessionMark(SessionMark.RECORDING_STOPPED, clip.title), end - start))
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
