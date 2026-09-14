package gg.sona.afterimage.protocol

data class RemoveEntityEffect(override val entityId: Int, val effectId: Int) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.REMOVE_ENTITY_EFFECT
}
