package gg.sona.afterimage.protocol

data class PluginMessage(val channel: String, val data: ByteArray) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.PLUGIN_MESSAGE
}
