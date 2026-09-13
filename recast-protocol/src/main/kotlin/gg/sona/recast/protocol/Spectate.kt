package gg.sona.recast.protocol

import java.util.*

data class Spectate(val target: UUID) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.SPECTATE
}
