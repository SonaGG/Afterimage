package gg.sona.afterimage.protocol

data class SpawnMob(
    override val entityId: Int,
    val type: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val yaw: Byte,
    val pitch: Byte,
    val headPitch: Byte,
    val velocityX: Int,
    val velocityY: Int,
    val velocityZ: Int,
    val metadata: List<MetadataEntry>,
) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.SPAWN_MOB
}
