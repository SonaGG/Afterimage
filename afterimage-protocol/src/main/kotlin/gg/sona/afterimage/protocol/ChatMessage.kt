package gg.sona.afterimage.protocol

data class ChatMessage(val json: String, val position: Int) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.CHAT_MESSAGE
}
