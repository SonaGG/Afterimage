package gg.sona.recast.protocol

data class OpenSignEditor(val position: Long) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.OPEN_SIGN_EDITOR
}
