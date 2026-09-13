package gg.sona.recast.protocol

data class MapChunkBulk(val skyLight: Boolean, val chunks: List<BulkChunk>) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.MAP_CHUNK_BULK
}
