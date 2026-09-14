package gg.sona.afterimage.protocol

data class MultiBlockChange(val chunkX: Int, val chunkZ: Int, val records: List<BlockChangeRecord>) :
    ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.MULTI_BLOCK_CHANGE
}
