package gg.sona.recast.protocol

data class SetSlot(val windowId: Int, val slot: Int, val item: ItemStack) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.SET_SLOT
}
