package gg.sona.afterimage.mc263.state

import com.mojang.authlib.GameProfile
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.protocol.InternalCodec
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundGameEventPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.GameType
import java.util.UUID

object SnapshotEncoder {
    fun encode(client: ShadowClient, nanos: Long): List<CapturedPacket> {
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

    fun packets(client: ShadowClient, nanos: Long): List<Packet<*>> {
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

    fun currentTime(client: ShadowClient, nanos: Long): ClientboundSetTimePacket? {
        val time = client.level.time ?: return null
        val elapsedTicks = ((nanos - client.level.timeUpdatedAtNanos) / 50_000_000L).coerceAtLeast(0L)
        return ClientboundSetTimePacket(time.gameTime() + elapsedTicks, time.clockUpdates())
    }

    fun entityPackets(client: ShadowClient, nanos: Long, out: MutableList<Packet<*>>) {
        val entities = client.entityMap.values().filter { it.visibleAt(nanos) }.sortedBy { it.id }
        val ghosts = ghostProfiles(client, entities, out)
        for (entity in entities) entity.spawnPackets(out, nanos)
        for (entity in entities) entity.attachmentPackets(out)
        if (ghosts.isNotEmpty()) out += ClientboundPlayerInfoRemovePacket(ghosts)
    }

    fun ghostProfiles(client: ShadowClient, spawned: List<ShadowEntity>, out: MutableList<Packet<*>>): List<UUID> {
        val players = client.players
        val ghosts = LinkedHashMap<UUID, ClientboundPlayerInfoUpdatePacket.Entry>()
        for (entity in spawned) {
            if (entity.entityType !== EntityTypes.PLAYER) continue
            val uuid = entity.add.uuid
            if (players.entries.containsKey(uuid) || ghosts.containsKey(uuid)) continue
            ghosts[uuid] = players.known[uuid]?.entry ?: ClientboundPlayerInfoUpdatePacket.Entry(
                uuid, GameProfile(uuid, uuid.toString().take(16)), false, 0, GameType.SURVIVAL, null, true, 0, null,
            )
        }
        if (ghosts.isNotEmpty()) out += ShadowPlayerList.addPacket(ghosts.values.toList())
        return ghosts.keys.toList()
    }
}
