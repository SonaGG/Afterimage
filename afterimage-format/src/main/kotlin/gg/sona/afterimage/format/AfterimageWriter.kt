package gg.sona.afterimage.format

import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketWriter
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class AfterimageWriter(
    val path: Path,
    val header: RecordingHeader,
    private val options: WriterOptions = WriterOptions(),
) : AutoCloseable {

    private val channel: FileChannel = FileChannel.open(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )

    private val pending = PacketWriter(options.maxSegmentRawBytes + 4096)
    private val pendingBlobs = PacketWriter(options.maxBlobSegmentRawBytes + 65536)
    private val pendingBlobRefs = ArrayList<BlobRef>()
    private val allSegments = ArrayList<SegmentInfo>()
    private val segments = ArrayList<SegmentInfo>()
    private val blobs = ArrayList<BlobRef>()
    private val knownChunks = HashSet<Long>()
    private var pendingCount = 0
    private var pendingStartNanos = 0L
    private var pendingLastNanos = 0L
    private var position = 0L
    private var lastSyncPosition = 0L
    private var lastSyncNanos = Long.MIN_VALUE
    private var closed = false

    var bytesWritten: Long = 0L
        private set

    var packetsWritten: Long = 0L
        private set

    var chunksDeduplicated: Long = 0L
        private set

    val segmentCount: Int get() = segments.size

    val lastTimestampNanos: Long
        get() = if (pendingCount > 0) pendingLastNanos else segments.lastOrNull()?.endNanos ?: 0L

    init {
        writeFully(HeaderCodec.encodeFileHeader(header))
    }

    fun append(packet: CapturedPacket) {
        check(!closed) { "writer is closed" }
        val timestamp = maxOf(packet.timestampNanos, lastTimestampNanos)
        for (record in transform(packet.withTimestamp(timestamp))) appendRecord(record)
    }

    private fun appendRecord(packet: CapturedPacket) {
        if (pendingCount == 0) {
            pendingStartNanos = packet.timestampNanos
            pendingLastNanos = packet.timestampNanos
        }
        PacketRecordCodec.write(pending, packet, pendingLastNanos)
        pendingLastNanos = packet.timestampNanos
        pendingCount++
        packetsWritten++
        if (shouldRoll()) flushDelta(pendingLastNanos)
    }

    fun writeSnapshot(nanos: Long, packets: List<CapturedPacket>) {
        check(!closed) { "writer is closed" }
        if (pendingCount > 0) flushDelta(maxOf(nanos, pendingLastNanos))
        val body = PacketWriter(64 * 1024)
        var count = 0
        for (packet in packets) {
            for (record in transform(packet.withTimestamp(nanos))) {
                PacketRecordCodec.write(body, record, nanos)
                count++
            }
        }
        flushBlobs(nanos)
        writeSegment(SegmentKind.SNAPSHOT, nanos, nanos, count, body, options.snapshotCompressor)
    }

    private fun transform(packet: CapturedPacket): List<CapturedPacket> {
        if (!options.dedupeChunks) return listOf(packet)
        val chunks = ChunkBlobs.chunksOf(packet) ?: return listOf(packet)
        val result = ArrayList<CapturedPacket>(chunks.size)
        for (chunk in chunks) {
            val hash = ChunkBlobs.hash(chunk.mask, chunk.skyLight, chunk.data)
            if (knownChunks.add(hash)) {
                val offset = pendingBlobs.size
                ChunkBlobs.writeBlob(pendingBlobs, ChunkBlob(hash, chunk.mask, chunk.skyLight, chunk.data))
                pendingBlobRefs += BlobRef(hash, -1, offset, pendingBlobs.size - offset)
                if (pendingBlobs.size >= options.maxBlobSegmentRawBytes) flushBlobs(packet.timestampNanos)
            } else {
                chunksDeduplicated++
            }
            result += ChunkBlobs.encodeRef(
                ChunkRef(
                    chunk.chunkX,
                    chunk.chunkZ,
                    chunk.mask,
                    chunk.skyLight,
                    chunk.bulk,
                    hash
                ), packet.timestampNanos
            )
        }
        return result
    }

    fun flush() {
        if (pendingCount > 0) flushDelta(pendingLastNanos)
        flushBlobs(lastTimestampNanos)
    }

    fun flushIfStale(nowNanos: Long) {
        if (pendingCount > 0 && nowNanos - pendingStartNanos >= options.maxSegmentDurationNanos) flushDelta(
            pendingLastNanos
        )
    }

    fun sync(nowNanos: Long = System.nanoTime()) {
        if (position == lastSyncPosition) return
        if (lastSyncNanos != Long.MIN_VALUE && nowNanos - lastSyncNanos < options.syncIntervalNanos) return
        channel.force(false)
        lastSyncPosition = position
        lastSyncNanos = nowNanos
    }

    fun segments(): List<SegmentInfo> = segments.toList()

    override fun close() {
        if (closed) return
        closed = true
        try {
            flush()
            writeFully(HeaderCodec.encodeIndex(allSegments, blobs, position))
            channel.force(true)
        } finally {
            channel.close()
        }
    }

    private fun shouldRoll(): Boolean =
        pending.size >= options.maxSegmentRawBytes ||
                pendingCount >= options.maxSegmentPackets ||
                pendingLastNanos - pendingStartNanos >= options.maxSegmentDurationNanos

    private fun flushDelta(endNanos: Long) {
        flushBlobs(endNanos)
        writeSegment(SegmentKind.DELTA, pendingStartNanos, endNanos, pendingCount, pending, options.compressor)
        pending.reset()
        pendingCount = 0
    }

    private fun flushBlobs(nanos: Long) {
        if (pendingBlobs.size == 0) return
        val segmentIndex = allSegments.size
        writeSegment(SegmentKind.BLOB, nanos, nanos, pendingBlobRefs.size, pendingBlobs, options.blobCompressor)
        for (ref in pendingBlobRefs) blobs += BlobRef(ref.hash, segmentIndex, ref.offset, ref.length)
        pendingBlobRefs.clear()
        pendingBlobs.reset()
    }

    private fun writeSegment(
        kind: SegmentKind,
        startNanos: Long,
        endNanos: Long,
        packetCount: Int,
        body: PacketWriter,
        compressor: SegmentCompressor
    ) {
        val stored = compressor.compress(body.rawBuffer(), body.size)
        val headerBytes =
            HeaderCodec.encodeSegmentHeader(kind, compressor.id, startNanos, endNanos, packetCount, body.size, stored)
        val offset = position
        writeFully(headerBytes)
        writeFully(stored)
        val info = SegmentInfo(
            if (kind == SegmentKind.BLOB) allSegments.size else segments.size,
            offset,
            kind,
            startNanos,
            endNanos,
            packetCount,
            body.size,
            stored.size
        )
        allSegments += info
        if (kind != SegmentKind.BLOB) segments += info
    }

    private fun writeFully(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer, position + buffer.position())
        position += bytes.size
        bytesWritten += bytes.size
    }
}
