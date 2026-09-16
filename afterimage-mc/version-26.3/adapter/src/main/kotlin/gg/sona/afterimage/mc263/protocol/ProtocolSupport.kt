package gg.sona.afterimage.mc263.protocol

import gg.sona.afterimage.format.ChunkPacketFormat
import gg.sona.afterimage.replay.protocol.ReplayProtocols
import gg.sona.afterimage.world.GameNames

object ProtocolSupport {
    fun install() {
        ReplayProtocols.register(McReplayProtocol.INSTANCE)
        ChunkPacketFormat.register(McChunkPacketFormat())
        GameNames.register(McGameNames.INSTANCE)
    }
}
