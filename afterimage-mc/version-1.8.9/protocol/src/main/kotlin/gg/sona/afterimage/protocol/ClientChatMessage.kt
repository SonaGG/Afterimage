package gg.sona.afterimage.protocol


data class ClientChatMessage(val message: String) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.CHAT_MESSAGE
}
