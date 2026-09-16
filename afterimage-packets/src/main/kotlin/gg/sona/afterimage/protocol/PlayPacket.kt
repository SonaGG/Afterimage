package gg.sona.afterimage.protocol

import gg.sona.afterimage.net.PacketDirection

interface PlayPacket {
    val packetId: Int
    val direction: PacketDirection
}

interface ClientboundPacket : PlayPacket {
    override val direction: PacketDirection get() = PacketDirection.CLIENTBOUND
}

interface ServerboundPacket : PlayPacket {
    override val direction: PacketDirection get() = PacketDirection.SERVERBOUND
}

interface EntityPacket : ClientboundPacket {
    val entityId: Int
}
