package gg.sona.recast.protocol

data class Respawn(val dimension: Int, val difficulty: Int, val gameMode: Int, val levelType: String) :
    ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.RESPAWN
}
