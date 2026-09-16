package gg.sona.afterimage.protocol

data class AttachEntity(override val entityId: Int, val vehicleId: Int, val leash: Boolean) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.ATTACH_ENTITY
}
