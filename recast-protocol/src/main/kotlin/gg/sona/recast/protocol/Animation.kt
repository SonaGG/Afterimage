package gg.sona.recast.protocol

data class Animation(override val entityId: Int, val animation: Int) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.ANIMATION

    companion object {
        const val SWING_ARM = 0
        const val TAKE_DAMAGE = 1
        const val LEAVE_BED = 2
        const val EAT_FOOD = 3
        const val CRITICAL_EFFECT = 4
        const val MAGIC_CRITICAL_EFFECT = 5
    }
}
