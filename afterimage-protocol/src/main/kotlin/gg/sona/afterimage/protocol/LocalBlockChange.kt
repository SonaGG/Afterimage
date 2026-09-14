package gg.sona.afterimage.protocol


data class LocalBlockChange(val position: Long, val state: Int) : ServerboundPacket {
    override val packetId: Int get() = AfterimageInternal.LOCAL_BLOCK_CHANGE
}
