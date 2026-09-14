package gg.sona.afterimage.protocol

data class Title(val action: Int, val textJson: String?, val fadeIn: Int, val stay: Int, val fadeOut: Int) :
    ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.TITLE

    companion object {
        const val SET_TITLE = 0
        const val SET_SUBTITLE = 1
        const val SET_TIMES = 2
        const val HIDE = 3
        const val RESET = 4
    }
}
