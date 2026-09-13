package gg.sona.recast.protocol


data class PlayerDigging(val status: Int, val position: Long, val face: Int) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.PLAYER_DIGGING

    companion object {
        const val START_DIGGING = 0
        const val CANCEL_DIGGING = 1
        const val FINISH_DIGGING = 2
        const val DROP_STACK = 3
        const val DROP_ITEM = 4
        const val RELEASE_USE_ITEM = 5
    }
}
