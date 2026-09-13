package gg.sona.recast.protocol


data class ClientPlayerAbilities(val flags: Int, val flyingSpeed: Float, val walkingSpeed: Float) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.PLAYER_ABILITIES
}
