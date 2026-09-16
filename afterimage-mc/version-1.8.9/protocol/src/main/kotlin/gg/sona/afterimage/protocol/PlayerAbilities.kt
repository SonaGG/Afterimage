package gg.sona.afterimage.protocol

data class PlayerAbilities(val flags: Int, val flyingSpeed: Float, val walkingSpeed: Float) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.PLAYER_ABILITIES

    companion object {
        const val INVULNERABLE = 0x01
        const val FLYING = 0x02
        const val ALLOW_FLYING = 0x04
        const val CREATIVE_MODE = 0x08
    }
}
