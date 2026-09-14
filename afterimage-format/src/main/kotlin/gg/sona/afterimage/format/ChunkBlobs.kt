package gg.sona.afterimage.format

import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.net.PacketWriter
import gg.sona.afterimage.protocol.*
import java.security.MessageDigest

object ChunkBlobs {

    private const val MAX_BULK_CHUNKS = 1024

    private val digest = ThreadLocal.withInitial { MessageDigest.getInstance("MD5") }

    fun hash(mask: Int, skyLight: Boolean, data: ByteArray): Long {
        val md = digest.get()
        md.reset()
        md.update((mask ushr 8).toByte())
        md.update(mask.toByte())
        md.update(if (skyLight) 1 else 0)
        md.update(data)
        val bytes = md.digest()
        var value = 0L
        for (index in 0 until 8) value = (value shl 8) or (bytes[index].toLong() and 0xFF)
        return value
    }

    fun isChunkPacket(packet: CapturedPacket): Boolean =
        packet.direction == PacketDirection.CLIENTBOUND && (packet.packetId == ClientboundPlay.CHUNK_DATA || packet.packetId == ClientboundPlay.MAP_CHUNK_BULK)

    fun chunksOf(packet: CapturedPacket): List<ChunkBlobEntry>? {
        if (!isChunkPacket(packet)) return null
        return try {
            if (packet.packetId == ClientboundPlay.CHUNK_DATA) parseChunkData(packet.payload) else parseBulk(packet.payload)
        } catch (error: RuntimeException) {
            null
        }
    }

    private fun parseChunkData(payload: ByteArray): List<ChunkBlobEntry>? {
        val reader = PacketReader(payload, 0, payload.size)
        if (reader.remaining < 11) return null
        val x = reader.readInt()
        val z = reader.readInt()
        val groundUpByte = reader.readUnsignedByte()
        if (groundUpByte != 1) return null
        val mask = reader.readUnsignedShort()
        if (mask == 0) return null
        val length = reader.readVarInt()
        if (length != reader.remaining) return null
        val withoutSky = ChunkLayout.dataSize(mask, skyLight = false, groundUp = true)
        val withSky = ChunkLayout.dataSize(mask, skyLight = true, groundUp = true)
        val skyLight = when (length) {
            withSky -> true
            withoutSky -> false
            else -> return null
        }
        return listOf(ChunkBlobEntry(x, z, mask, skyLight, reader.readBytes(length), bulk = false))
    }

    private fun parseBulk(payload: ByteArray): List<ChunkBlobEntry>? {
        val reader = PacketReader(payload, 0, payload.size)
        if (reader.remaining < 2) return null
        val skyByte = reader.readUnsignedByte()
        if (skyByte > 1) return null
        val skyLight = skyByte == 1
        val count = reader.readVarInt()
        if (count !in 1..MAX_BULK_CHUNKS || reader.remaining < count * 10) return null
        val xs = IntArray(count)
        val zs = IntArray(count)
        val masks = IntArray(count)
        var total = 0L
        for (index in 0 until count) {
            xs[index] = reader.readInt()
            zs[index] = reader.readInt()
            masks[index] = reader.readUnsignedShort()
            total += ChunkLayout.dataSize(masks[index], skyLight, groundUp = true)
        }
        if (total != reader.remaining.toLong()) return null
        val result = ArrayList<ChunkBlobEntry>(count)
        for (index in 0 until count) {
            result += ChunkBlobEntry(
                xs[index],
                zs[index],
                masks[index],
                skyLight,
                reader.readBytes(ChunkLayout.dataSize(masks[index], skyLight, groundUp = true)),
                bulk = true
            )
        }
        return result
    }

    fun encodeRef(ref: ChunkRef, timestampNanos: Long): CapturedPacket {
        val writer = PacketWriter(24)
        writer.writeInt(ref.chunkX).writeInt(ref.chunkZ).writeShort(ref.mask)
            .writeByte((if (ref.skyLight) 1 else 0) or (if (ref.bulk) 2 else 0)).writeLong(ref.hash)
        return CapturedPacket(
            PacketDirection.CLIENTBOUND,
            timestampNanos,
            AfterimageInternal.CHUNK_REF,
            writer.toByteArray()
        )
    }

    fun decodeRef(packet: CapturedPacket): ChunkRef {
        val reader = PacketReader(packet.payload, 0, packet.payload.size)
        val x = reader.readInt()
        val z = reader.readInt()
        val mask = reader.readUnsignedShort()
        val flags = reader.readUnsignedByte()
        return ChunkRef(x, z, mask, flags and 1 != 0, flags and 2 != 0, reader.readLong())
    }

    fun isRef(packet: CapturedPacket): Boolean =
        packet.direction == PacketDirection.CLIENTBOUND && packet.packetId == AfterimageInternal.CHUNK_REF

    fun materialize(ref: ChunkRef, blob: ChunkBlob, timestampNanos: Long): CapturedPacket = if (ref.bulk) {
        PacketCodec.encode(
            MapChunkBulk(ref.skyLight, listOf(BulkChunk(ref.chunkX, ref.chunkZ, ref.mask, blob.data))),
            timestampNanos
        )
    } else {
        PacketCodec.encode(ChunkData(ref.chunkX, ref.chunkZ, true, ref.mask, blob.data), timestampNanos)
    }

    fun writeBlob(writer: PacketWriter, blob: ChunkBlob) {
        writer.writeLong(blob.hash).writeShort(blob.mask).writeByte(if (blob.skyLight) 1 else 0)
            .writeVarInt(blob.data.size).writeBytes(blob.data)
    }

    fun readBlobs(body: ByteArray, onBlob: (offset: Int, length: Int, blob: ChunkBlob) -> Unit) {
        val reader = PacketReader(body, 0, body.size)
        while (reader.hasRemaining()) {
            val offset = reader.position
            val hash = reader.readLong()
            val mask = reader.readUnsignedShort()
            val skyLight = reader.readUnsignedByte() != 0
            val length = reader.readVarInt()
            if (length < 0 || length > reader.remaining) throw AfterimageFormatException("chunk blob length $length exceeds segment")
            val data = reader.readBytes(length)
            onBlob(offset, reader.position - offset, ChunkBlob(hash, mask, skyLight, data))
        }
    }

    fun readBlobAt(body: ByteArray, offset: Int): ChunkBlob {
        val reader = PacketReader(body, offset, body.size)
        val hash = reader.readLong()
        val mask = reader.readUnsignedShort()
        val skyLight = reader.readUnsignedByte() != 0
        val length = reader.readVarInt()
        if (length < 0 || length > reader.remaining) throw AfterimageFormatException("chunk blob length $length exceeds segment")
        return ChunkBlob(hash, mask, skyLight, reader.readBytes(length))
    }
}
