package gg.sona.recast.protocol

data class BlockChange(val position: Long, val blockState: Int) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.BLOCK_CHANGE
}
