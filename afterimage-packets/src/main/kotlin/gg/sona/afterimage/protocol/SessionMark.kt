package gg.sona.afterimage.protocol


data class SessionMark(val kind: Int, val label: String) : ServerboundPacket {
    override val packetId: Int get() = AfterimageInternal.SESSION_MARK

    companion object {
        const val RECORDING_STARTED = 0
        const val RECORDING_STOPPED = 1
        const val USER_MARKER = 2
        const val DISCONNECTED = 3
    }
}
