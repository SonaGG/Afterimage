package gg.sona.afterimage.protocol

data class EntityVelocity(override val entityId: Int, val velocityX: Int, val velocityY: Int, val velocityZ: Int) :
    EntityPacket {
    override val packetId: Int get() = ClientboundPlay.ENTITY_VELOCITY
}
