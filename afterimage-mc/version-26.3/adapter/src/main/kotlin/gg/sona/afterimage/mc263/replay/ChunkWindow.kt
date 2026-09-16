package gg.sona.afterimage.mc263.replay

import gg.sona.afterimage.replay.session.ReplaySession
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket
import net.minecraft.util.Mth
import net.minecraft.world.level.ChunkPos
import kotlin.math.abs
import kotlin.math.max

class ChunkWindow(private val minecraft: Minecraft) {
    private val scratch = ArrayList<Packet<*>>()
    private var frame = 0
    private var lastCenterX = Int.MIN_VALUE
    private var lastCenterZ = Int.MIN_VALUE

    fun update(replay: ReplaySession, mirror: VirtualConnection, cameraX: Double, cameraZ: Double) {
        val level = minecraft.level ?: return
        if (!mirror.isOpen) return
        val shadow = replay.shadow
        val radius = minecraft.options.effectiveRenderDistance.coerceAtLeast(2)
        val centerX = Mth.floor(cameraX) shr 4
        val centerZ = Mth.floor(cameraZ) shr 4
        val storage = level.chunkSource.storage
        val nanos = replay.positionNanos
        if (storage.chunkRadius != radius.coerceAtLeast(2) + 3) deliver(shadow, mirror, ClientboundSetChunkCacheRadiusPacket(radius), nanos)
        if (storage.viewCenterX != centerX || storage.viewCenterZ != centerZ) {
            deliver(shadow, mirror, ClientboundSetChunkCacheCenterPacket(centerX, centerZ), nanos)
        }
        val moved = centerX != lastCenterX || centerZ != lastCenterZ
        lastCenterX = centerX
        lastCenterZ = centerZ
        if (!moved && frame++ % SCAN_INTERVAL != 0) return
        var budget = CHUNKS_PER_FRAME
        val chunks = shadow.level.chunks.values.filter { chunk ->
            val packet = chunk.chunk.packet
            max(abs(packet.x - centerX), abs(packet.z - centerZ)) <= radius && !level.chunkSource.hasChunk(packet.x, packet.z)
        }.sortedBy { chunk -> max(abs(chunk.chunk.packet.x - centerX), abs(chunk.chunk.packet.z - centerZ)) }
        for (chunk in chunks) {
            if (budget-- <= 0) break
            scratch.clear()
            chunk.emit(scratch)
            for (packet in scratch) deliver(shadow, mirror, packet, nanos)
        }
    }

    private fun deliver(shadow: gg.sona.afterimage.mc263.state.ShadowClient, mirror: VirtualConnection, packet: Packet<*>, nanos: Long) {
        val encoded = shadow.codecs.encode(packet, nanos) ?: return
        mirror.replayEvent(encoded)
    }

    private companion object {
        const val CHUNKS_PER_FRAME = 6
        const val SCAN_INTERVAL = 10
    }
}
