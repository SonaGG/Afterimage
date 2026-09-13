package gg.sona.recast.protocol

data class EntityMetadata(override val entityId: Int, val metadata: List<MetadataEntry>) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.ENTITY_METADATA
}
