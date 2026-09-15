package gg.sona.afterimage.protocol


data class EntityAction(val entityId: Int, val action: Int, val jumpBoost: Int) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.ENTITY_ACTION

    companion object {
        const val START_SNEAKING = 0
        const val STOP_SNEAKING = 1
        const val LEAVE_BED = 2
        const val START_SPRINTING = 3
        const val STOP_SPRINTING = 4
        const val JUMP_WITH_HORSE = 5
        const val OPEN_HORSE_INVENTORY = 6
    }
}
