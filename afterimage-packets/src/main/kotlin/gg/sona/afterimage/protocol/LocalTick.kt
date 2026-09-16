package gg.sona.afterimage.protocol


object LocalTick : ServerboundPacket {
    override val packetId: Int get() = AfterimageInternal.LOCAL_TICK

    override fun toString(): String = "LocalTick"
}
