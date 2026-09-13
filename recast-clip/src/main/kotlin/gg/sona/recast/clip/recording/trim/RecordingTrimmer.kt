package gg.sona.recast.clip.recording.trim

import gg.sona.recast.clip.recording.combine.CombineResult
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
            RecastWriter(output, header, writerOptions).use { writer ->
                val session = ReplaySession(input)
                session.load()
                session.seek(start)
                val interval = input.header.keyframeIntervalNanos
                writer.writeSnapshot(0L, session.shadow.snapshot(start))
                keyframes++
                var lastKeyframe = 0L
                val consumer = object : ReplayConsumer {
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
                    PacketCodec.encode(
                        SessionMark(SessionMark.RECORDING_STOPPED, source.fileName.toString()),
                        end - start
                    )
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
