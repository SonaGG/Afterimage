package gg.sona.afterimage.protocol

data class SetCompression(val threshold: Int) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.SET_COMPRESSION
}
