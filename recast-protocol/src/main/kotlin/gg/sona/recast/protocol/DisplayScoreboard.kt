package gg.sona.recast.protocol

data class DisplayScoreboard(val position: Int, val name: String) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.DISPLAY_SCOREBOARD

    companion object {
        const val LIST = 0
        const val SIDEBAR = 1
        const val BELOW_NAME = 2
    }
}
