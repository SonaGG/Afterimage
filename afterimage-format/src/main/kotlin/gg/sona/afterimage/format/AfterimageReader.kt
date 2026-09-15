package gg.sona.afterimage.format

import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketReader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class AfterimageReader private constructor(
    val path: Path,
    private val channel: FileChannel,
    val header: RecordingHeader,
    val allSegments: List<SegmentInfo>,
    blobDirectory: List<BlobRef>?,
    val recovered: Boolean,
) : AutoCloseable {

    private val compressors = HashMap<Int, SegmentCompressor>()
    private val chunkFormat = ChunkPacketFormat.forProtocol(header.protocolVersion)
    private val blobSegments: Map<Int, SegmentInfo> =
        allSegments.filter { it.kind == SegmentKind.BLOB }.associateBy { it.index }
    private val blobs: Map<Long, BlobRef> = (blobDirectory ?: scanBlobs()).associateBy { it.hash }
    private val blobBodies = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean =
            size > BLOB_CACHE_SEGMENTS
    }

    val segments: List<SegmentInfo> = allSegments.filter { it.kind != SegmentKind.BLOB }

    val startNanos: Long get() = segments.firstOrNull()?.startNanos ?: 0L

    val endNanos: Long get() = segments.lastOrNull()?.endNanos ?: 0L

    val durationNanos: Long get() = endNanos - startNanos

    val snapshotSegments: List<SegmentInfo> = segments.filter { it.isSnapshot }

    fun blobDirectory(): List<BlobRef> = blobs.values.sortedWith(compareBy({ it.segmentIndex }, { it.offset }))

    fun readSegmentBody(segment: SegmentInfo): ByteArray {
        val headerBytes = readAt(segment.offset, AfterimageFormat.SEGMENT_HEADER_BYTES)
        val parsed = HeaderCodec.decodeSegmentHeader(PacketReader(headerBytes, 0, headerBytes.size))
            ?: throw AfterimageFormatException("segment ${segment.index} header is corrupt")
        val stored = readAt(segment.bodyOffset, parsed.storedLength)
        if (HeaderCodec.crc32(stored) != parsed.crc) throw AfterimageFormatException("segment ${segment.index} failed its checksum")
        return compressor(parsed.codec).decompress(stored, parsed.rawLength)
    }

    fun readSegment(segment: SegmentInfo): List<CapturedPacket> {
        if (segment.kind == SegmentKind.BLOB) return emptyList()
        val records = PacketRecordCodec.readAll(readSegmentBody(segment), segment.startNanos, segment.packetCount)
        if (records.none { ChunkBlobs.isRef(it) }) return records
        val result = ArrayList<CapturedPacket>(records.size)
        for (record in records) {
            if (!ChunkBlobs.isRef(record)) {
                result += record
                continue
            }
            val ref = ChunkBlobs.decodeRef(record)
            val blob = blob(ref.hash) ?: continue
            result += chunkFormat.materialize(ref, blob, record.timestampNanos)
        }
        return result
    }

    fun blob(hash: Long): ChunkBlob? {
        val ref = blobs[hash] ?: return null
        val segment = blobSegments[ref.segmentIndex] ?: return null
        val body = synchronized(blobBodies) { blobBodies.getOrPut(ref.segmentIndex) { readSegmentBody(segment) } }
        return ChunkBlobs.readBlobAt(body, ref.offset)
    }

    private fun scanBlobs(): List<BlobRef> {
        val result = ArrayList<BlobRef>()
        for (segment in blobSegments.values.sortedBy { it.index }) {
            val body = runCatching { readSegmentBody(segment) }.getOrNull() ?: continue
            ChunkBlobs.readBlobs(body) { offset, length, blob ->
                result += BlobRef(
                    blob.hash,
                    segment.index,
                    offset,
                    length
                )
            }
        }
        return result
    }

    fun snapshotAtOrBefore(nanos: Long): SegmentInfo? {
        var low = 0
        var high = snapshotSegments.size - 1
        var best: SegmentInfo? = null
        while (low <= high) {
            val middle = (low + high) ushr 1
            val candidate = snapshotSegments[middle]
            if (candidate.startNanos <= nanos) {
                best = candidate
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best
    }

    private fun compressor(id: Int): SegmentCompressor = compressors.getOrPut(id) { SegmentCompressor.ofId(id) }

    private fun readAt(offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes)
        var position = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read < 0) throw AfterimageFormatException("unexpected end of recording at $position")
            position += read
        }
        return bytes
    }

    override fun close() {
        channel.close()
    }

    companion object {

        private const val BLOB_CACHE_SEGMENTS = 12

        fun open(path: Path): AfterimageReader {
            val channel = FileChannel.open(path, StandardOpenOption.READ)
            try {
                val size = channel.size()
                val headerBytes = read(channel, 0L, minOf(size, 8L + 65535L).toInt())
                val headerReader = PacketReader(headerBytes, 0, headerBytes.size)
                val header = HeaderCodec.decodeFileHeader(headerReader)
                val firstSegmentOffset = headerReader.position.toLong()
                val indexed = readIndex(channel, size, firstSegmentOffset)
                val segments = indexed?.segments ?: scanSegments(channel, size, firstSegmentOffset)
                return AfterimageReader(path, channel, header, segments, indexed?.blobs, indexed == null)
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }

        fun repair(path: Path): Boolean {
            val reader = open(path)
            val segments = reader.allSegments
            val needsRepair = reader.recovered
            val blobs = reader.blobDirectory()
            reader.close()
            if (!needsRepair) return false
            val end = segments.lastOrNull()?.let { it.bodyOffset + it.storedLength } ?: firstSegmentOffset(path)
            FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                channel.truncate(end)
                val index = ByteBuffer.wrap(HeaderCodec.encodeIndex(segments, blobs, end))
                var position = end
                while (index.hasRemaining()) position += channel.write(index, position)
                channel.force(true)
            }
            return true
        }

        private fun firstSegmentOffset(path: Path): Long =
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val headerBytes = read(channel, 0L, minOf(channel.size(), 8L + 65535L).toInt())
                val reader = PacketReader(headerBytes, 0, headerBytes.size)
                HeaderCodec.decodeFileHeader(reader)
                reader.position.toLong()
            }

        private class IndexData(val segments: List<SegmentInfo>, val blobs: List<BlobRef>)

        private fun readIndex(channel: FileChannel, size: Long, firstSegmentOffset: Long): IndexData? {
            if (size < firstSegmentOffset + AfterimageFormat.TRAILER_BYTES) return null
            val trailer = read(channel, size - AfterimageFormat.TRAILER_BYTES, AfterimageFormat.TRAILER_BYTES)
            val trailerReader = PacketReader(trailer, 0, trailer.size)
            val indexOffset = trailerReader.readLong()
            if (!trailerReader.readBytes(4).contentEquals(AfterimageFormat.TRAILER_MAGIC)) return null
            if (indexOffset < firstSegmentOffset || indexOffset >= size - AfterimageFormat.TRAILER_BYTES) return null
            val indexBytes = read(channel, indexOffset, (size - AfterimageFormat.TRAILER_BYTES - indexOffset).toInt())
            val reader = PacketReader(indexBytes, 0, indexBytes.size)
            if (!reader.readBytes(4).contentEquals(AfterimageFormat.INDEX_MAGIC)) return null
            val count = reader.readInt()
            if (count < 0 || reader.remaining < count * AfterimageFormat.INDEX_ENTRY_BYTES) return null
            val segments = ArrayList<SegmentInfo>(count)
            var packetIndex = 0
            for (index in 0 until count) {
                val offset = reader.readLong()
                val kind = SegmentKind.ofId(reader.readUnsignedByte())
                val start = reader.readLong()
                val end = reader.readLong()
                val packetCount = reader.readInt()
                val rawLength = reader.readInt()
                val storedLength = reader.readInt()
                if (offset < firstSegmentOffset || offset + AfterimageFormat.SEGMENT_HEADER_BYTES + storedLength > indexOffset) return null
                val segmentIndex = if (kind == SegmentKind.BLOB) index else packetIndex++
                segments += SegmentInfo(segmentIndex, offset, kind, start, end, packetCount, rawLength, storedLength)
            }
            val blobs = ArrayList<BlobRef>()
            if (reader.remaining >= 8 && reader.readBytes(4).contentEquals(AfterimageFormat.BLOB_MAGIC)) {
                val blobCount = reader.readInt()
                if (blobCount < 0 || reader.remaining < blobCount * AfterimageFormat.BLOB_ENTRY_BYTES) return null
                repeat(blobCount) {
                    blobs += BlobRef(
                        reader.readLong(),
                        reader.readInt(),
                        reader.readInt(),
                        reader.readInt()
                    )
                }
            }
            return IndexData(segments, blobs)
        }

        private fun scanSegments(channel: FileChannel, size: Long, firstSegmentOffset: Long): List<SegmentInfo> {
            val segments = ArrayList<SegmentInfo>()
            var offset = firstSegmentOffset
            var packetIndex = 0
            while (offset + AfterimageFormat.SEGMENT_HEADER_BYTES <= size) {
                val headerBytes = read(channel, offset, AfterimageFormat.SEGMENT_HEADER_BYTES)
                val parsed = HeaderCodec.decodeSegmentHeader(PacketReader(headerBytes, 0, headerBytes.size)) ?: break
                if (parsed.storedLength < 0 || parsed.rawLength < 0 || parsed.packetCount < 0) break
                val bodyOffset = offset + AfterimageFormat.SEGMENT_HEADER_BYTES
                if (bodyOffset + parsed.storedLength > size) break
                val stored = read(channel, bodyOffset, parsed.storedLength)
                if (HeaderCodec.crc32(stored) != parsed.crc) break
                val segmentIndex = if (parsed.kind == SegmentKind.BLOB) segments.size else packetIndex++
                segments += SegmentInfo(
                    segmentIndex,
                    offset,
                    parsed.kind,
                    parsed.startNanos,
                    parsed.endNanos,
                    parsed.packetCount,
                    parsed.rawLength,
                    parsed.storedLength
                )
                offset = bodyOffset + parsed.storedLength
            }
            return segments
        }

        private fun read(channel: FileChannel, offset: Long, length: Int): ByteArray {
            val bytes = ByteArray(length)
            val buffer = ByteBuffer.wrap(bytes)
            var position = offset
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position)
                if (read < 0) break
                position += read
            }
            return if (buffer.hasRemaining()) bytes.copyOf(buffer.position()) else bytes
        }
    }
}
