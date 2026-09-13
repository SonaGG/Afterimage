package gg.sona.recast.protocol

data class HeldItemChange(val slot: Int) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.HELD_ITEM_CHANGE
}
