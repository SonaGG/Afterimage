package gg.sona.recast.protocol

data class EntityLook(override val entityId: Int, val yaw: Byte, val pitch: Byte, val onGround: Boolean) :
    EntityPacket {
    override val packetId: Int get() = ClientboundPlay.ENTITY_LOOK
}
