package gg.sona.recast.protocol

data class ConfirmTransaction(val windowId: Int, val action: Int, val accepted: Boolean) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.CONFIRM_TRANSACTION
}
