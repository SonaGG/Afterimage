package gg.sona.recast.protocol

data class UpdateScore(val name: String, val action: Int, val objective: String, val value: Int) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.UPDATE_SCORE

    companion object {
        const val CHANGE = 0
        const val REMOVE = 1
    }
}
