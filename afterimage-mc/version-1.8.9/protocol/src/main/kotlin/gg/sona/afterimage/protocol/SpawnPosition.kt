package gg.sona.afterimage.protocol

data class SpawnPosition(val position: Long) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.SPAWN_POSITION
}
