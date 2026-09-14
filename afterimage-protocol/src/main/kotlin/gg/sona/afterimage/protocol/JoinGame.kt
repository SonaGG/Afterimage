package gg.sona.afterimage.protocol

data class JoinGame(
    override val entityId: Int,
    val gameMode: Int,
    val dimension: Int,
    val difficulty: Int,
    val maxPlayers: Int,
    val levelType: String,
    val reducedDebugInfo: Boolean,
) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.JOIN_GAME
}
