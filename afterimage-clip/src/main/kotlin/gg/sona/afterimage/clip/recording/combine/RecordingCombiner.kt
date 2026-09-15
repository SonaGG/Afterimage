package gg.sona.afterimage.clip.recording.combine

import gg.sona.afterimage.core.time.Nanos
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

class RecordingCombiner(
    private val writerOptions: WriterOptions = WriterOptions(),
    private val gapNanos: Long = Nanos.ofSeconds(1)
) {
    fun combine(
        sources: List<Path>,
        output: Path,
        ranges: List<LongRange?>,
        progress: (Double) -> Unit = {}
    ): CombineResult {
        require(sources.size >= 2) { "combining needs at least two recordings" }
        Files.createDirectories(output.toAbsolutePath().parent)
        val first = FileReplaySource.open(sources.first())
        val header = first.use { first ->
            RecordingHeader(
                sessionId = UUID.randomUUID(),
                protocolVersion = first.header.protocolVersion,
                startEpochMillis = first.header.startEpochMillis,
                keyframeIntervalNanos = first.header.keyframeIntervalNanos,
                metadata = first.header.metadata + mapOf(COMBINED_SOURCES to sources.joinToString(";") { it.fileName.toString() }),
            )
        }
        var packets = 0L
        var keyframes = 0
        var offset = 0L
        val lengths = ArrayList<Long>(sources.size)
        AfterimageWriter(output, header, writerOptions).use { writer ->
            for ((index, path) in sources.withIndex()) {
                FileReplaySource.open(path).use { source ->
                    val session = ReplaySession(source)
                    session.load()
                    val range = ranges.getOrNull(index)
                    val start = (range?.first ?: source.startNanos).coerceIn(source.startNanos, source.endNanos)
                    val end = (range?.last ?: source.endNanos).coerceIn(start, source.endNanos)
                    session.seek(start)
                    val interval = source.header.keyframeIntervalNanos
                    writer.writeSnapshot(offset, session.state.snapshot(start))
                    keyframes++
                    writer.append(
                        session.protocol.marker(SessionMarks.USER_MARKER, path.fileName.toString().substringBeforeLast('.'), offset)
                    )
                    var lastKeyframe = offset
                    val base = offset
                    val consumer = object : ReplayConsumer {
                        override fun onPacket(packet: CapturedPacket, mode: DeliveryMode) {
                            if (packet.timestampNanos <= start) return
                            val relative = base + (packet.timestampNanos - start)
                            writer.append(packet.withTimestamp(relative))
                            packets++
                            if (relative - lastKeyframe >= interval) {
                                writer.writeSnapshot(relative, session.state.snapshot(packet.timestampNanos))
                                lastKeyframe = relative
                                keyframes++
                            }
                            val total = sources.size.toDouble()
                            if (end > start) progress(
                                ((index + (packet.timestampNanos - start).toDouble() / (end - start)) / total).coerceIn(
                                    0.0,
                                    1.0
                                )
                            )
                        }
                    }
                    session.addConsumer(consumer)
                    session.seek(end, linear = true)
                    session.removeConsumer(consumer)
                    offset = base + (end - start)
                    lengths += end - start
                    writer.append(
                        session.protocol.marker(SessionMarks.RECORDING_STOPPED, path.fileName.toString(), offset)
                    )
                    offset += gapNanos
                }
            }
        }
        progress(1.0)
        return CombineResult(output, packets, keyframes, offset, lengths)
    }

    companion object {
        const val COMBINED_SOURCES = "combined.sources"
    }
}
