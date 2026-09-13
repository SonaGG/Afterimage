package gg.sona.recast.protocol

data class UpdateHealth(val health: Float, val food: Int, val saturation: Float) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.UPDATE_HEALTH
}
