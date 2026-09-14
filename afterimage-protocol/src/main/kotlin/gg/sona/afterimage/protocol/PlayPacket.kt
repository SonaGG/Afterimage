package gg.sona.afterimage.protocol

import gg.sona.afterimage.net.PacketDirection

sealed interface PlayPacket {
    val packetId: Int
    val direction: PacketDirection
}

sealed interface ClientboundPacket : PlayPacket {
    override val direction: PacketDirection get() = PacketDirection.CLIENTBOUND
}

sealed interface ServerboundPacket : PlayPacket {
    override val direction: PacketDirection get() = PacketDirection.SERVERBOUND
}

sealed interface EntityPacket : ClientboundPacket {
    val entityId: Int
}
