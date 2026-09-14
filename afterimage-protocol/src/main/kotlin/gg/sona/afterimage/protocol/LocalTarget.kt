package gg.sona.afterimage.protocol


data class LocalTarget(val position: Long, val face: Int) : ServerboundPacket {
    override val packetId: Int get() = AfterimageInternal.LOCAL_TARGET

    val isBlock: Boolean get() = face in 0..5

    companion object {
        val NONE = LocalTarget(0L, -1)
    }
}
