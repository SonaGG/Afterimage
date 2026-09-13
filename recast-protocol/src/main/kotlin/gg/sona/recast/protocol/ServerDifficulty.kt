package gg.sona.recast.protocol

data class ServerDifficulty(val difficulty: Int) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.SERVER_DIFFICULTY
}
