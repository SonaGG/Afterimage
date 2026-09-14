package gg.sona.afterimage.protocol

data class Disconnect(val reasonJson: String) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.DISCONNECT
}
