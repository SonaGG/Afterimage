package gg.sona.recast.protocol


data class LocalBlockBreak(val position: Long, val stage: Int) : ServerboundPacket {
    override val packetId: Int get() = RecastInternal.LOCAL_BLOCK_BREAK
}
