package gg.sona.recast.protocol


data class ClientPlayerLook(val yaw: Float, val pitch: Float, val onGround: Boolean) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.PLAYER_LOOK
}
