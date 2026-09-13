package gg.sona.recast.protocol


object LocalTick : ServerboundPacket {
    override val packetId: Int get() = RecastInternal.LOCAL_TICK

    override fun toString(): String = "LocalTick"
}
