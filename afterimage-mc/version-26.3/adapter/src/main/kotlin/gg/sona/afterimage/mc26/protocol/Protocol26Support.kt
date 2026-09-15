package gg.sona.afterimage.mc26.protocol

import gg.sona.afterimage.format.ChunkPacketFormat
import gg.sona.afterimage.replay.protocol.ReplayProtocols
import gg.sona.afterimage.world.GameNames

object Protocol26Support {
    fun install() {
        ReplayProtocols.register(Protocol26.INSTANCE)
        ChunkPacketFormat.register(ChunkPackets26())
        GameNames.register(Names26.INSTANCE)
    }
}
