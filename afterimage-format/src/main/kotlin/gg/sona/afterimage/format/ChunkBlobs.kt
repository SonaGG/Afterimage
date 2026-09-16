package gg.sona.afterimage.format

import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.net.PacketWriter
import java.security.MessageDigest

object ChunkBlobs {
    const val CHUNK_REF_PACKET_ID = 0x106

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

    fun encodeRef(ref: ChunkRef, timestampNanos: Long): CapturedPacket {
        val writer = PacketWriter(24)
        writer.writeInt(ref.chunkX).writeInt(ref.chunkZ).writeShort(ref.mask)
            .writeByte((if (ref.skyLight) 1 else 0) or (if (ref.bulk) 2 else 0)).writeLong(ref.hash)
        return CapturedPacket(PacketDirection.CLIENTBOUND, timestampNanos, CHUNK_REF_PACKET_ID, writer.toByteArray())
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
        packet.direction == PacketDirection.CLIENTBOUND && packet.packetId == CHUNK_REF_PACKET_ID

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
