package gg.sona.afterimage.protocol

import java.util.*

data class Spectate(val target: UUID) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.SPECTATE
}
