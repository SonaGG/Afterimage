package gg.sona.afterimage.protocol

data class TimeUpdate(val worldAge: Long, val timeOfDay: Long) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.TIME_UPDATE
}
