package gg.sona.afterimage.clip.recording.trim

import gg.sona.afterimage.clip.recording.combine.CombineResult
import gg.sona.afterimage.format.AfterimageWriter
import gg.sona.afterimage.format.RecordingHeader
import gg.sona.afterimage.format.WriterOptions
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.replay.protocol.SessionMarks
import gg.sona.afterimage.replay.consumer.DeliveryMode
import gg.sona.afterimage.replay.consumer.ReplayConsumer
import gg.sona.afterimage.replay.session.ReplaySession
import gg.sona.afterimage.replay.source.FileReplaySource
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class RecordingTrimmer(private val writerOptions: WriterOptions = WriterOptions()) {
    fun trim(
        source: Path,
        output: Path,
        startNanos: Long,
        endNanos: Long,
        progress: (Double) -> Unit = {}
    ): CombineResult {
        require(endNanos > startNanos) { "trim range is empty" }
        Files.createDirectories(output.toAbsolutePath().parent)
        var packets = 0L
        var keyframes = 0
        FileReplaySource.open(source).use { input ->
            val start = startNanos.coerceIn(input.startNanos, input.endNanos)
            val end = endNanos.coerceIn(start, input.endNanos)
            val header = RecordingHeader(
                sessionId = UUID.randomUUID(),
                protocolVersion = input.header.protocolVersion,
                startEpochMillis = input.header.startEpochMillis + (start - input.startNanos) / 1_000_000L,
                keyframeIntervalNanos = input.header.keyframeIntervalNanos,
                metadata = input.header.metadata + mapOf(TRIMMED_FROM to source.fileName.toString()),
            )
            AfterimageWriter(output, header, writerOptions).use { writer ->
                val session = ReplaySession(input)
                session.load()
                session.seek(start)
                val interval = input.header.keyframeIntervalNanos
                writer.writeSnapshot(0L, session.state.snapshot(start))
                keyframes++
                var lastKeyframe = 0L
                val consumer = object : ReplayConsumer {
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
                        if (end > start) progress(
                            ((packet.timestampNanos - start).toDouble() / (end - start)).coerceIn(
                                0.0,
                                1.0
                            )
                        )
                    }
                }
                session.addConsumer(consumer)
                session.seek(end, linear = true)
                session.removeConsumer(consumer)
                writer.append(
                    session.protocol.marker(SessionMarks.RECORDING_STOPPED, source.fileName.toString(), end - start)
                )
            }
            progress(1.0)
            return CombineResult(output, packets, keyframes, end - start)
        }
    }

    companion object {
        const val TRIMMED_FROM = "trimmed.from"
    }
}
