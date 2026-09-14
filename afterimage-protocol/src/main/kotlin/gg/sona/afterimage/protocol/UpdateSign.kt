package gg.sona.afterimage.protocol

data class UpdateSign(val position: Long, val lines: List<String>) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.UPDATE_SIGN
}
