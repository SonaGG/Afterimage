package gg.sona.recast.protocol

data class OpenWindow(
    val windowId: Int,
    val type: String,
    val titleJson: String,
    val slotCount: Int,
    val horseEntityId: Int
) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.OPEN_WINDOW
}
