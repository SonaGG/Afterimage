package gg.sona.afterimage.protocol


data class LocalBlockBreak(val position: Long, val stage: Int) : ServerboundPacket {
    override val packetId: Int get() = AfterimageInternal.LOCAL_BLOCK_BREAK
}
