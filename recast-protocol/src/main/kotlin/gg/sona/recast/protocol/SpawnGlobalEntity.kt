package gg.sona.recast.protocol

data class SpawnGlobalEntity(override val entityId: Int, val type: Int, val x: Int, val y: Int, val z: Int) :
    EntityPacket {
    override val packetId: Int get() = ClientboundPlay.SPAWN_GLOBAL_ENTITY
}
