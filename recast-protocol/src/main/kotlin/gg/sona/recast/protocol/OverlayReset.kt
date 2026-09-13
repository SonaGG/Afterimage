package gg.sona.recast.protocol


data class OverlayReset(val flags: Int, val chat: List<OverlayChatLine> = emptyList()) : ServerboundPacket {
    override val packetId: Int get() = RecastInternal.OVERLAY_RESET

    companion object {
        const val CHAT = 1
    }
}
