package gg.sona.afterimage.protocol

data class ServerDifficulty(val difficulty: Int) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.SERVER_DIFFICULTY
}
