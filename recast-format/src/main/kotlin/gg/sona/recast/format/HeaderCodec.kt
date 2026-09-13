package gg.sona.recast.format

import gg.sona.recast.net.PacketReader
import gg.sona.recast.net.PacketWriter
import java.util.zip.CRC32

object HeaderCodec {

    fun encodeFileHeader(header: RecordingHeader): ByteArray {
        val body = PacketWriter(128)
        body.writeUuid(header.sessionId)
        body.writeInt(header.protocolVersion)
        body.writeLong(header.startEpochMillis)
        body.writeLong(header.keyframeIntervalNanos)
        body.writeShort(header.metadata.size)
        for ((key, value) in header.metadata) {
            body.writeString(key)
            body.writeString(value)
        }
        val writer = PacketWriter(body.size + 8)
        writer.writeBytes(RecastFormat.FILE_MAGIC)
        writer.writeShort(RecastFormat.VERSION)
        writer.writeShort(body.size)
        writer.writeBytes(body.rawBuffer(), 0, body.size)
        return writer.toByteArray()
    }

    fun decodeFileHeader(reader: PacketReader): RecordingHeader {
        val magic = reader.readBytes(4)
        if (!magic.contentEquals(RecastFormat.FILE_MAGIC)) throw RecastFormatException("not a recast recording")
        val version = reader.readUnsignedShort()
        if (version > RecastFormat.VERSION) throw RecastFormatException("recording format $version is newer than supported ${RecastFormat.VERSION}")
        val length = reader.readUnsignedShort()
        val body = reader.slice(length)
        val sessionId = body.readUuid()
        val protocolVersion = body.readInt()
        val startEpochMillis = body.readLong()
        val keyframeInterval = body.readLong()
        val count = body.readUnsignedShort()
        val metadata = LinkedHashMap<String, String>(count)
        repeat(count) { metadata[body.readString()] = body.readString() }
        return RecordingHeader(sessionId, protocolVersion, startEpochMillis, keyframeInterval, metadata)
    }

    fun encodeSegmentHeader(
        kind: SegmentKind,
        codec: Int,
        startNanos: Long,
        endNanos: Long,
        packetCount: Int,
        rawLength: Int,
        stored: ByteArray,
    ): ByteArray {
        val writer = PacketWriter(RecastFormat.SEGMENT_HEADER_BYTES)
        writer.writeBytes(RecastFormat.SEGMENT_MAGIC)
        writer.writeByte(kind.id)
        writer.writeByte(codec)
        writer.writeLong(startNanos)
        writer.writeLong(endNanos)
        writer.writeInt(packetCount)
        writer.writeInt(rawLength)
        writer.writeInt(stored.size)
        writer.writeInt(crc32(stored))
        return writer.toByteArray()
    }

    class SegmentHeader(
        val kind: SegmentKind,
        val codec: Int,
        val startNanos: Long,
        val endNanos: Long,
        val packetCount: Int,
        val rawLength: Int,
        val storedLength: Int,
        val crc: Int,
    )

    fun decodeSegmentHeader(reader: PacketReader): SegmentHeader? {
        if (reader.remaining < RecastFormat.SEGMENT_HEADER_BYTES) return null
        val magic = reader.readBytes(4)
        if (!magic.contentEquals(RecastFormat.SEGMENT_MAGIC)) return null
        return SegmentHeader(
            SegmentKind.ofId(reader.readUnsignedByte()),
            reader.readUnsignedByte(),
            reader.readLong(),
            reader.readLong(),
            reader.readInt(),
            reader.readInt(),
            reader.readInt(),
            reader.readInt(),
        )
    }

    fun encodeIndex(segments: List<SegmentInfo>, blobs: List<BlobRef>, indexOffset: Long): ByteArray {
        val writer =
            PacketWriter(24 + segments.size * RecastFormat.INDEX_ENTRY_BYTES + blobs.size * RecastFormat.BLOB_ENTRY_BYTES + RecastFormat.TRAILER_BYTES)
        writer.writeBytes(RecastFormat.INDEX_MAGIC)
        writer.writeInt(segments.size)
        for (segment in segments) {
            writer.writeLong(segment.offset)
            writer.writeByte(segment.kind.id)
            writer.writeLong(segment.startNanos)
            writer.writeLong(segment.endNanos)
            writer.writeInt(segment.packetCount)
            writer.writeInt(segment.rawLength)
            writer.writeInt(segment.storedLength)
        }
        writer.writeBytes(RecastFormat.BLOB_MAGIC)
        writer.writeInt(blobs.size)
        for (blob in blobs) {
            writer.writeLong(blob.hash)
            writer.writeInt(blob.segmentIndex)
            writer.writeInt(blob.offset)
            writer.writeInt(blob.length)
        }
        writer.writeLong(indexOffset)
        writer.writeBytes(RecastFormat.TRAILER_MAGIC)
        return writer.toByteArray()
    }

    fun crc32(bytes: ByteArray): Int {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value.toInt()
    }
}
