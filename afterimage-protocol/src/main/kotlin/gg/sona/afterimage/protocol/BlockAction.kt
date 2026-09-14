package gg.sona.afterimage.protocol

data class BlockAction(val position: Long, val byte1: Int, val byte2: Int, val blockType: Int) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.BLOCK_ACTION
}
