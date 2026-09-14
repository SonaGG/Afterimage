package gg.sona.afterimage.capture.packet

import gg.sona.afterimage.net.PacketDirection

interface PacketSink {
    fun offer(direction: PacketDirection, packetId: Int, payload: ByteArray)
    fun offer(direction: PacketDirection, packetId: Int, source: ByteArray, offset: Int, length: Int) {
        offer(direction, packetId, source.copyOfRange(offset, offset + length))
    }
}