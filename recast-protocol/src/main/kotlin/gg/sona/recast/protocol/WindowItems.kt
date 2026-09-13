package gg.sona.recast.protocol

data class WindowItems(val windowId: Int, val items: List<ItemStack>) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.WINDOW_ITEMS
}
