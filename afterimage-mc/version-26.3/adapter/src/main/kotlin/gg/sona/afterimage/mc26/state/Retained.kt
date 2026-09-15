package gg.sona.afterimage.mc26.state

import gg.sona.afterimage.net.CapturedPacket
import net.minecraft.network.protocol.Packet

class Retained<T : Packet<*>>(val packet: T, val hash: Long) {
    fun same(other: Retained<*>?): Boolean = other != null && other.hash == hash

    companion object {
        private const val OFFSET = 1469598103934665603L
        private const val PRIME = 1099511628211L

        fun hashOf(packet: CapturedPacket): Long = hashOf(packet.payload, packet.packetId.toLong())

        fun hashOf(bytes: ByteArray, seed: Long = 0L): Long {
            var hash = OFFSET xor seed
            for (byte in bytes) {
                hash = hash xor (byte.toLong() and 0xFF)
                hash *= PRIME
            }
            return hash
        }

        fun <T : Packet<*>> of(packet: T, source: CapturedPacket): Retained<T> = Retained(packet, hashOf(source))
    }
}
