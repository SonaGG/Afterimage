package gg.sona.recast.protocol

data class ResourcePackSend(val url: String, val hash: String) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.RESOURCE_PACK_SEND
}
