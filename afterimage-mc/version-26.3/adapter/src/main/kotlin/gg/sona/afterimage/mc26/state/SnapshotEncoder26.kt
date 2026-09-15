package gg.sona.afterimage.mc26.state

import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.protocol.InternalCodec
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundGameEventPacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket

object SnapshotEncoder26 {
    fun encode(client: ShadowClient26, nanos: Long): List<CapturedPacket> {
        if (!client.joined) return emptyList()
        val out = ArrayList<CapturedPacket>(client.configuration.size + 256)
        for (packet in client.configuration) out += packet.withTimestamp(nanos)
        for (packet in packets(client, nanos)) client.codecs.encode(packet, nanos)?.let { out += it }
        val vanilla = ArrayList<Packet<*>>(4)
        val reset = client.overlays.snapshot(nanos, client.codecs.registries, vanilla)
        for (packet in vanilla) client.codecs.encode(packet, nanos)?.let { out += it }
        out += InternalCodec.encode(reset, nanos)
        if (client.localPlayer.screen.isOpen) out += InternalCodec.encode(client.localPlayer.screen, nanos)
        client.localPlayer.target?.let { out += InternalCodec.encode(it, nanos) }
        return out
    }

    fun packets(client: ShadowClient26, nanos: Long): List<Packet<*>> {
        val login = client.login ?: return emptyList()
        val out = ArrayList<Packet<*>>(256)
        out += login
        client.respawn?.let { out += it }
        client.level.metaPackets(out)
        currentTime(client, nanos)?.let { out += it }
        client.level.gameEvents[ClientboundGameEventPacket.CHANGE_GAME_MODE]?.let { out += it.packet }
        client.players.snapshot(out)
        if (client.localPlayer.hasPosition) out += client.currentPositionPacket()
        client.scoreboard.snapshot(out)
        client.level.chunkPackets(out)
        entityPackets(client, nanos, out)
        client.localPlayer.snapshot(out, nanos)
        client.level.extraPackets(out)
        return out
    }

    fun currentTime(client: ShadowClient26, nanos: Long): ClientboundSetTimePacket? {
        val time = client.level.time ?: return null
        val elapsedTicks = ((nanos - client.level.timeUpdatedAtNanos) / 50_000_000L).coerceAtLeast(0L)
        return ClientboundSetTimePacket(time.gameTime() + elapsedTicks, time.clockUpdates())
    }

    fun entityPackets(client: ShadowClient26, nanos: Long, out: MutableList<Packet<*>>) {
        val entities = client.entityMap.values().filter { it.visibleAt(nanos) }.sortedBy { it.id }
        for (entity in entities) entity.spawnPackets(out, nanos)
        for (entity in entities) entity.attachmentPackets(out)
    }
}
