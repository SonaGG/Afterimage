package gg.sona.afterimage.protocol47

import gg.sona.afterimage.format.ChunkBlob
import gg.sona.afterimage.format.ChunkBlobEntry
import gg.sona.afterimage.format.ChunkPacketFormat
import gg.sona.afterimage.format.ChunkRef
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.protocol.BulkChunk
import gg.sona.afterimage.protocol.ChunkData
import gg.sona.afterimage.protocol.ChunkLayout
import gg.sona.afterimage.protocol.ClientboundPlay
import gg.sona.afterimage.protocol.MapChunkBulk
import gg.sona.afterimage.protocol.PacketCodec
import gg.sona.afterimage.protocol.Protocol

class ChunkPackets47 : ChunkPacketFormat {
    override val protocolVersion: Int get() = Protocol.VERSION

    override fun chunksOf(packet: CapturedPacket): List<ChunkBlobEntry>? {
        if (packet.direction != PacketDirection.CLIENTBOUND) return null
        if (packet.packetId != ClientboundPlay.CHUNK_DATA && packet.packetId != ClientboundPlay.MAP_CHUNK_BULK) return null
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

    override fun materialize(ref: ChunkRef, blob: ChunkBlob, timestampNanos: Long): CapturedPacket = if (ref.bulk) {
        PacketCodec.encode(MapChunkBulk(ref.skyLight, listOf(BulkChunk(ref.chunkX, ref.chunkZ, ref.mask, blob.data))), timestampNanos)
    } else {
        PacketCodec.encode(ChunkData(ref.chunkX, ref.chunkZ, true, ref.mask, blob.data), timestampNanos)
    }

    private companion object {
        const val MAX_BULK_CHUNKS = 1024
    }
}
