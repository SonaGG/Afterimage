package gg.sona.afterimage.protocol

data class CloseWindow(val windowId: Int) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.CLOSE_WINDOW
}
