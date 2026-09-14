package gg.sona.afterimage.protocol

data class SetExperience(val bar: Float, val level: Int, val total: Int) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.SET_EXPERIENCE
}
