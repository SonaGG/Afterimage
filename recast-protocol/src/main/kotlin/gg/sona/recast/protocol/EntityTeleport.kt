package gg.sona.recast.protocol

data class EntityTeleport(
    override val entityId: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val yaw: Byte,
    val pitch: Byte,
    val onGround: Boolean
) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.ENTITY_TELEPORT
}
