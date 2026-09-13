package gg.sona.recast.protocol

data class EntityProperties(override val entityId: Int, val attributes: List<EntityAttribute>) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.ENTITY_PROPERTIES
}
