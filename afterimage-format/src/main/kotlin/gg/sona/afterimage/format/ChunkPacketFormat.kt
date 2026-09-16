package gg.sona.afterimage.format

import gg.sona.afterimage.net.CapturedPacket
import java.util.*

interface ChunkPacketFormat {
    val protocolVersion: Int

    fun chunksOf(packet: CapturedPacket): List<ChunkBlobEntry>?

    fun materialize(ref: ChunkRef, blob: ChunkBlob, timestampNanos: Long): CapturedPacket

    object None : ChunkPacketFormat {
        override val protocolVersion: Int get() = -1
        override fun chunksOf(packet: CapturedPacket): List<ChunkBlobEntry>? = null
        override fun materialize(ref: ChunkRef, blob: ChunkBlob, timestampNanos: Long): CapturedPacket =
            throw AfterimageFormatException("no chunk packet format is registered for this recording")
    }

    companion object {
        private val registered = LinkedHashMap<Int, ChunkPacketFormat>()

        @Volatile
        private var discovered = false

        fun register(format: ChunkPacketFormat) {
            synchronized(registered) { registered[format.protocolVersion] = format }
        }

        fun forProtocol(version: Int): ChunkPacketFormat {
            if (!discovered) synchronized(registered) {
                if (!discovered) {
                    discovered = true
                    for (format in ServiceLoader.load(ChunkPacketFormat::class.java, ChunkPacketFormat::class.java.classLoader)) {
                        registered.putIfAbsent(format.protocolVersion, format)
                    }
                }
            }
            synchronized(registered) { return registered[version] ?: None }
        }
    }
}
