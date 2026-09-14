package gg.sona.afterimage.protocol

data class CombatEvent(
    val event: Int,
    val duration: Int,
    val playerId: Int,
    val entityId: Int,
    val messageJson: String?
) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.COMBAT_EVENT

    companion object {
        const val ENTER_COMBAT = 0
        const val END_COMBAT = 1
        const val ENTITY_DEAD = 2
    }
}
