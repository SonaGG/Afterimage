package gg.sona.recast.protocol

data class SpawnExperienceOrb(override val entityId: Int, val x: Int, val y: Int, val z: Int, val count: Int) :
    EntityPacket {
    override val packetId: Int get() = ClientboundPlay.SPAWN_EXPERIENCE_ORB
}
