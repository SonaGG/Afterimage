package gg.sona.afterimage.protocol

data class EntityIdle(override val entityId: Int) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.ENTITY
}
