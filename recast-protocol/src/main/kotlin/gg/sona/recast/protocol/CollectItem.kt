package gg.sona.recast.protocol

data class CollectItem(val collectedId: Int, val collectorId: Int) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.COLLECT_ITEM
}
