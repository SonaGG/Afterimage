package gg.sona.afterimage.protocol

data class ScoreboardObjective(val name: String, val mode: Int, val displayName: String?, val type: String?) :
    ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.SCOREBOARD_OBJECTIVE

    companion object {
        const val CREATE = 0
        const val REMOVE = 1
        const val UPDATE = 2
    }
}
