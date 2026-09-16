package gg.sona.afterimage.protocol


data class ClientPlayerLook(val yaw: Float, val pitch: Float, val onGround: Boolean) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.PLAYER_LOOK
}
