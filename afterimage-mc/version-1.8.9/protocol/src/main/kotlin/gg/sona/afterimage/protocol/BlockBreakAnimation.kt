package gg.sona.afterimage.protocol

data class BlockBreakAnimation(override val entityId: Int, val position: Long, val stage: Int) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.BLOCK_BREAK_ANIMATION
}
