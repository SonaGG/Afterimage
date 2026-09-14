package gg.sona.afterimage.protocol


data class ClientPluginMessage(val channel: String, val data: ByteArray) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.PLUGIN_MESSAGE
}
