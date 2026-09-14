package gg.sona.afterimage.protocol

data class EntityHeadLook(override val entityId: Int, val headYaw: Byte) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.ENTITY_HEAD_LOOK
}
