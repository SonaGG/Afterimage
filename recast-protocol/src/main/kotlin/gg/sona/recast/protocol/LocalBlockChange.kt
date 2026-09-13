package gg.sona.recast.protocol


data class LocalBlockChange(val position: Long, val state: Int) : ServerboundPacket {
    override val packetId: Int get() = RecastInternal.LOCAL_BLOCK_CHANGE
}
