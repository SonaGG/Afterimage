package gg.sona.recast.protocol

data class CloseWindow(val windowId: Int) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.CLOSE_WINDOW
}
