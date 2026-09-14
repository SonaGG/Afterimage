package gg.sona.afterimage.protocol


data class ClientStatus(val action: Int) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.CLIENT_STATUS

    companion object {
        const val PERFORM_RESPAWN = 0
        const val REQUEST_STATS = 1
        const val OPEN_INVENTORY_ACHIEVEMENT = 2
    }
}
