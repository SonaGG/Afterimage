package gg.sona.afterimage.protocol47

import gg.sona.afterimage.format.ChunkPacketFormat
import gg.sona.afterimage.replay.protocol.ReplayProtocols
import gg.sona.afterimage.world.GameNames

object Protocol47Support {
    fun install() {
        ReplayProtocols.register(Protocol47.INSTANCE)
        ChunkPacketFormat.register(ChunkPackets47())
        GameNames.register(Names47.INSTANCE)
    }
}
