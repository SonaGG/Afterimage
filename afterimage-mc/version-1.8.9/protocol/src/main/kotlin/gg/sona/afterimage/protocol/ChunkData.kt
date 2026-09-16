package gg.sona.afterimage.protocol

data class ChunkData(val chunkX: Int, val chunkZ: Int, val groundUp: Boolean, val mask: Int, val data: ByteArray) :
    ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.CHUNK_DATA

    val isUnload: Boolean get() = groundUp && mask == 0

    override fun equals(other: Any?): Boolean =
        other is ChunkData && chunkX == other.chunkX && chunkZ == other.chunkZ && groundUp == other.groundUp && mask == other.mask && data.contentEquals(
            other.data
        )

    override fun hashCode(): Int = (chunkX * 31 + chunkZ) * 31 + mask
}
