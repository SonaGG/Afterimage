package gg.sona.afterimage.protocol

data class PlayerPositionAndLook(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val flags: Int,
) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.PLAYER_POSITION_AND_LOOK

    fun isRelative(bit: Int): Boolean = flags and bit != 0

    companion object {
        const val RELATIVE_X = 0x01
        const val RELATIVE_Y = 0x02
        const val RELATIVE_Z = 0x04
        const val RELATIVE_YAW = 0x08
        const val RELATIVE_PITCH = 0x10
    }
}
