package gg.sona.recast.protocol


data class UseEntity(val targetId: Int, val type: Int, val hitX: Float, val hitY: Float, val hitZ: Float) :
    ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.USE_ENTITY

    companion object {
        const val INTERACT = 0
        const val ATTACK = 1
        const val INTERACT_AT = 2
    }
}
