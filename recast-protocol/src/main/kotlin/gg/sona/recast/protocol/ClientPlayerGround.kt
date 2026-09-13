package gg.sona.recast.protocol


data class ClientPlayerGround(val onGround: Boolean) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.PLAYER
}
