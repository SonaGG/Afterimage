package gg.sona.afterimage.protocol

data class WindowProperty(val windowId: Int, val property: Int, val value: Int) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.WINDOW_PROPERTY
}
