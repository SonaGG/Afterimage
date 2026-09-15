package gg.sona.afterimage.protocol

data class PlayerListHeaderFooter(val headerJson: String, val footerJson: String) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.PLAYER_LIST_HEADER_FOOTER
}
