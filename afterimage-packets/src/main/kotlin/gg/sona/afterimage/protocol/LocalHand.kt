package gg.sona.afterimage.protocol


data class LocalHand(val action: Int, val hand: Int, val animation: Int = 0, val duration: Int = 0) : ServerboundPacket {
    override val packetId: Int get() = AfterimageInternal.LOCAL_HAND

    val isSwing: Boolean get() = action == SWING

    companion object {
        const val SWING = 0
        const val RESET_ATTACK = 1
        const val ITEM_USED = 2
        const val MAIN_HAND = 0
        const val OFF_HAND = 1
    }
}
