package gg.sona.afterimage.protocol


data class LocalScreen(
    val kind: Int,
    val windowId: Int,
    val mouseX: Int,
    val mouseY: Int,
    val guiWidth: Int,
    val guiHeight: Int,
    val text: String,
    val cursor: Int,
    val detail: Int,
    val position: Long = 0L,
    val flags: Int = 0,
) : ServerboundPacket {
    override val packetId: Int get() = AfterimageInternal.LOCAL_SCREEN

    val isOpen: Boolean get() = kind != NONE

    val hudHidden: Boolean get() = flags and FLAG_HIDE_GUI != 0

    val playerListShown: Boolean get() = flags and FLAG_PLAYER_LIST != 0

    val perspective: Int get() = (flags shr PERSPECTIVE_SHIFT) and PERSPECTIVE_MASK

    fun sameLayout(other: LocalScreen): Boolean =
        kind == other.kind && windowId == other.windowId && text == other.text && cursor == other.cursor && detail == other.detail && position == other.position && flags == other.flags

    companion object {
        const val NONE = 0
        const val INVENTORY = 1
        const val CREATIVE = 2
        const val CONTAINER = 3
        const val CHAT = 4
        const val PAUSE = 5
        const val DEATH = 6
        const val SIGN = 7
        const val BOOK = 8
        const val OTHER = 9
        const val MAX_TEXT = 1024
        const val FLAG_HIDE_GUI = 1
        const val FLAG_PLAYER_LIST = 2
        const val PERSPECTIVE_SHIFT = 2
        const val PERSPECTIVE_MASK = 3
        val CLOSED = LocalScreen(NONE, 0, 0, 0, 0, 0, "", 0, 0)

        fun perspectiveFlags(perspective: Int): Int = (perspective and PERSPECTIVE_MASK) shl PERSPECTIVE_SHIFT
    }
}
