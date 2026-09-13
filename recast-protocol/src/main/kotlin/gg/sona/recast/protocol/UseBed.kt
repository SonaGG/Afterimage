package gg.sona.recast.protocol

data class UseBed(override val entityId: Int, val position: Long) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.USE_BED
}
