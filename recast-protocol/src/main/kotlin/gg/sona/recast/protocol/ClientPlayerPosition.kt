package gg.sona.recast.protocol


data class ClientPlayerPosition(val x: Double, val y: Double, val z: Double, val onGround: Boolean) :
    ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.PLAYER_POSITION
}
