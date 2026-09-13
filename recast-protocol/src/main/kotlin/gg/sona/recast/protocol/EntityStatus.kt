package gg.sona.recast.protocol

data class EntityStatus(override val entityId: Int, val status: Int) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.ENTITY_STATUS

    companion object {
        const val HURT = 2
        const val DEAD = 3
    }
}
