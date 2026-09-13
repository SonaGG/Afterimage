package gg.sona.recast.protocol

data class Disconnect(val reasonJson: String) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.DISCONNECT
}
