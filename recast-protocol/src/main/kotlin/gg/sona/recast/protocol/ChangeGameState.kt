package gg.sona.recast.protocol

data class ChangeGameState(val reason: Int, val value: Float) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.CHANGE_GAME_STATE

    companion object {
        const val INVALID_BED = 0
        const val END_RAINING = 1
        const val BEGIN_RAINING = 2
        const val CHANGE_GAME_MODE = 3
        const val ENTER_CREDITS = 4
        const val DEMO_MESSAGE = 5
        const val ARROW_HIT = 6
        const val RAIN_STRENGTH = 7
        const val THUNDER_STRENGTH = 8
        const val GUARDIAN_APPEARANCE = 10
    }
}
