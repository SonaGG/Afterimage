package gg.sona.afterimage.format

import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.net.PacketWriter

object PacketRecordCodec {

    fun write(writer: PacketWriter, packet: CapturedPacket, previousNanos: Long) {
        writer.writeVarInt((packet.packetId shl 1) or packet.direction.bit)
        writer.writeVarLong(packet.timestampNanos - previousNanos)
        writer.writeVarInt(packet.payload.size)
        writer.writeBytes(packet.payload)
    }

    fun read(reader: PacketReader, previousNanos: Long): CapturedPacket {
        val head = reader.readVarInt()
        val nanos = previousNanos + reader.readVarLong()
        val length = reader.readVarInt()
        if (length < 0 || length > reader.remaining) throw AfterimageFormatException("packet record length $length exceeds segment")
        return CapturedPacket(PacketDirection.ofBit(head and 1), nanos, head ushr 1, reader.readBytes(length))
    }

    fun readAll(body: ByteArray, startNanos: Long, expectedCount: Int): List<CapturedPacket> {
        val reader = PacketReader(body, 0, body.size)
        val packets = ArrayList<CapturedPacket>(expectedCount)
        var previous = startNanos
        while (reader.hasRemaining()) {
            val packet = read(reader, previous)
            previous = packet.timestampNanos
            packets += packet
        }
        if (expectedCount >= 0 && packets.size != expectedCount) {
            throw AfterimageFormatException("segment declared $expectedCount packets but contained ${packets.size}")
        }
        return packets
    }
}
