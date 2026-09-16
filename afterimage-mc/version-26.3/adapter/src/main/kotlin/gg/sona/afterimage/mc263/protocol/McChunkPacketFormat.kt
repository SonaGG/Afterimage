package gg.sona.afterimage.mc263.protocol

import gg.sona.afterimage.format.ChunkBlob
import gg.sona.afterimage.format.ChunkBlobEntry
import gg.sona.afterimage.format.ChunkPacketFormat
import gg.sona.afterimage.format.ChunkRef
import gg.sona.afterimage.mc263.net.PacketIds
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.net.PacketWriter

class McChunkPacketFormat : ChunkPacketFormat {
    override val protocolVersion: Int get() = PacketIds.protocolVersion

    override fun chunksOf(packet: CapturedPacket): List<ChunkBlobEntry>? {
        if (packet.direction != PacketDirection.CLIENTBOUND || packet.packetId != PacketIds.LEVEL_CHUNK_WITH_LIGHT) return null
        if (packet.payload.size < 8) return null
        val reader = PacketReader(packet.payload, 0, packet.payload.size)
        val x = reader.readInt()
        val z = reader.readInt()
        return listOf(ChunkBlobEntry(x, z, 0, false, reader.readRemaining(), bulk = false))
    }

    override fun materialize(ref: ChunkRef, blob: ChunkBlob, timestampNanos: Long): CapturedPacket {
        val writer = PacketWriter(blob.data.size + 8)
        writer.writeInt(ref.chunkX).writeInt(ref.chunkZ).writeBytes(blob.data)
        return CapturedPacket(PacketDirection.CLIENTBOUND, timestampNanos, PacketIds.LEVEL_CHUNK_WITH_LIGHT, writer.toByteArray())
    }
}
