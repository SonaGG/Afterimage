package gg.sona.afterimage.protocol

data class WorldEffect(val effectId: Int, val position: Long, val data: Int, val disableRelativeVolume: Boolean) :
    ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.EFFECT
}
