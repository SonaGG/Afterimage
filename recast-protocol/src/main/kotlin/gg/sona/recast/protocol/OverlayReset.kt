package gg.sona.recast.protocol

data class OverlayReset(
    val flags: Int,
    val chat: List<OverlayChatLine> = emptyList(),
    val titleAgeNanos: Long = NONE,
    val actionBarAgeNanos: Long = NONE,
) : ServerboundPacket {
    override val packetId: Int get() = RecastInternal.OVERLAY_RESET

    val resetsChat: Boolean get() = flags and CHAT != 0
    val resetsTitle: Boolean get() = flags and TITLE != 0
    val resetsActionBar: Boolean get() = flags and ACTION_BAR != 0

    companion object {
        const val CHAT = 1
        const val TITLE = 2
        const val ACTION_BAR = 4
        const val NONE = -1L
    }
}
