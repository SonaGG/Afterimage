package gg.sona.recast.protocol

data class Teams(
    val name: String,
    val mode: Int,
    val displayName: String?,
    val prefix: String?,
    val suffix: String?,
    val friendlyFire: Int,
    val nameTagVisibility: String?,
    val color: Int,
    val players: List<String>,
) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.TEAMS

    companion object {
        const val CREATE = 0
        const val REMOVE = 1
        const val UPDATE = 2
        const val ADD_PLAYERS = 3
        const val REMOVE_PLAYERS = 4
    }
}
