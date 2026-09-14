package gg.sona.afterimage.protocol

data class DestroyEntities(val entityIds: IntArray) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.DESTROY_ENTITIES

    override fun equals(other: Any?): Boolean = other is DestroyEntities && entityIds.contentEquals(other.entityIds)

    override fun hashCode(): Int = entityIds.contentHashCode()
}
