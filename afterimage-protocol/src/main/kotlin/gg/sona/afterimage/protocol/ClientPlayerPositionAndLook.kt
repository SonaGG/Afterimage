package gg.sona.afterimage.protocol


data class ClientPlayerPositionAndLook(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val onGround: Boolean,
) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.PLAYER_POSITION_AND_LOOK
}
