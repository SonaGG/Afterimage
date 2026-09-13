package gg.sona.recast.protocol

data class EntityRelativeMove(
    override val entityId: Int,
    val deltaX: Byte,
    val deltaY: Byte,
    val deltaZ: Byte,
    val onGround: Boolean
) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.ENTITY_RELATIVE_MOVE
}
