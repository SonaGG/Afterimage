package gg.sona.afterimage.clip.recording.compact

import gg.sona.afterimage.format.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class RecordingCompactor(private val writerOptions: WriterOptions = WriterOptions()) {
    fun compact(source: Path, output: Path, progress: (Double) -> Unit = {}): CompactResult {
        Files.createDirectories(output.toAbsolutePath().parent)
        var packets = 0L
        var deduplicated = 0L
        AfterimageReader.open(source).use { reader ->
            val header = RecordingHeader(
                sessionId = reader.header.sessionId,
                protocolVersion = reader.header.protocolVersion,
                startEpochMillis = reader.header.startEpochMillis,
                keyframeIntervalNanos = reader.header.keyframeIntervalNanos,
                metadata = reader.header.metadata,
            )
            AfterimageWriter(output, header, writerOptions).use { writer ->
                val segments = reader.segments
                for ((index, segment) in segments.withIndex()) {
                    val loaded = reader.readSegment(segment)
                    if (segment.kind == SegmentKind.SNAPSHOT) {
                        writer.writeSnapshot(segment.startNanos, loaded)
                    } else {
                        for (packet in loaded) {
                            writer.append(packet)
                            packets++
                        }
                    }
                    progress((index + 1).toDouble() / segments.size)
                }
                deduplicated = writer.chunksDeduplicated
            }
        }
        return CompactResult(Files.size(source), Files.size(output), packets, deduplicated)
    }

    fun compactInPlace(path: Path, progress: (Double) -> Unit = {}): CompactResult {
        val temporary = path.resolveSibling(path.fileName.toString() + ".compact")
        val result = compact(path, temporary, progress)
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return result
    }
}