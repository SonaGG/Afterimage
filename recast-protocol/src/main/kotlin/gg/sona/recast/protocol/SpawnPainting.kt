package gg.sona.recast.protocol

data class SpawnPainting(override val entityId: Int, val title: String, val position: Long, val facing: Int) :
    EntityPacket {
    override val packetId: Int get() = ClientboundPlay.SPAWN_PAINTING
}
