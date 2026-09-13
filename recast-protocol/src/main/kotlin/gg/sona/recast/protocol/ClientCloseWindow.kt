package gg.sona.recast.protocol


data class ClientCloseWindow(val windowId: Int) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.CLOSE_WINDOW
}
