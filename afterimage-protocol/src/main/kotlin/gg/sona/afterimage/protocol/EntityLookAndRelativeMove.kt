package gg.sona.afterimage.protocol

data class EntityLookAndRelativeMove(
    override val entityId: Int,
    val deltaX: Byte,
    val deltaY: Byte,
    val deltaZ: Byte,
    val yaw: Byte,
    val pitch: Byte,
    val onGround: Boolean,
) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.ENTITY_LOOK_AND_RELATIVE_MOVE
}
