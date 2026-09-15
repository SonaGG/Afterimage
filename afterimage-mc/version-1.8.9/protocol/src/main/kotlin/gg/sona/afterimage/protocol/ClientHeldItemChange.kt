package gg.sona.afterimage.protocol


data class ClientHeldItemChange(val slot: Int) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.HELD_ITEM_CHANGE
}
