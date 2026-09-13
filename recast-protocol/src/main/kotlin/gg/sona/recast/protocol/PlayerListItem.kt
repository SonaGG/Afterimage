package gg.sona.recast.protocol

data class PlayerListItem(val action: Int, val entries: List<PlayerListEntry>) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.PLAYER_LIST_ITEM

    companion object {
        const val ADD_PLAYER = 0
        const val UPDATE_GAME_MODE = 1
        const val UPDATE_LATENCY = 2
        const val UPDATE_DISPLAY_NAME = 3
        const val REMOVE_PLAYER = 4
    }
}
