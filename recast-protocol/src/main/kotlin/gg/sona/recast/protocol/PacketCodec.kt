package gg.sona.recast.protocol

import gg.sona.recast.net.CapturedPacket
import gg.sona.recast.net.PacketDirection
import gg.sona.recast.net.PacketFormatException
import gg.sona.recast.net.PacketWriter

object PacketCodec {

    fun decode(packet: CapturedPacket): PlayPacket? {
        val reader = packet.reader()
        return try {
            when (packet.direction) {
                PacketDirection.CLIENTBOUND -> ClientboundCodec.decode(packet.packetId, reader)
                PacketDirection.SERVERBOUND -> ServerboundCodec.decode(packet.packetId, reader)
            }
        } catch (error: PacketFormatException) {
            null
        } catch (error: IndexOutOfBoundsException) {
            null
        }
    }

    fun decodeStrict(packet: CapturedPacket): PlayPacket? {
        val reader = packet.reader()
        return when (packet.direction) {
            PacketDirection.CLIENTBOUND -> ClientboundCodec.decode(packet.packetId, reader)
            PacketDirection.SERVERBOUND -> ServerboundCodec.decode(packet.packetId, reader)
        }
    }

    fun encode(packet: PlayPacket, timestampNanos: Long, writer: PacketWriter = PacketWriter()): CapturedPacket {
        writer.reset()
        encodeInto(packet, writer)
        return CapturedPacket(packet.direction, timestampNanos, packet.packetId, writer.toByteArray())
    }

    fun encodeInto(packet: PlayPacket, writer: PacketWriter) {
        when (packet) {
            is ClientboundPacket -> ClientboundCodec.encode(packet, writer)
            is ServerboundPacket -> ServerboundCodec.encode(packet, writer)
        }
    }

    fun encodeAll(packets: Iterable<PlayPacket>, timestampNanos: Long): List<CapturedPacket> {
        val writer = PacketWriter(1024)
        return packets.map { encode(it, timestampNanos, writer) }
    }
}
