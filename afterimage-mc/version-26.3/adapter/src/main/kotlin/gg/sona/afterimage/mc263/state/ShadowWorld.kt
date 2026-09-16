package gg.sona.afterimage.mc263.state

import gg.sona.afterimage.mc263.mixin.BossEventAccessor
import gg.sona.afterimage.mc263.mixin.SectionBlocksUpdateAccessor
import gg.sona.afterimage.net.CapturedPacket
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket
import net.minecraft.network.protocol.game.ClientboundGameEventPacket
import net.minecraft.network.protocol.game.ClientboundGameRuleValuesPacket
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket
import net.minecraft.world.BossEvent
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.PalettedContainerFactory
import java.util.*

class ShadowWorld {
    val chunks = LinkedHashMap<Long, ShadowChunk>()
    val bossBars = LinkedHashMap<UUID, ArrayList<Retained<ClientboundBossEventPacket>>>()
    val maps = LinkedHashMap<Int, ArrayList<Retained<ClientboundMapItemDataPacket>>>()
    val waypoints = LinkedHashMap<Any, Retained<ClientboundTrackedWaypointPacket>>()
    val resourcePacks = LinkedHashMap<UUID, Retained<ClientboundResourcePackPushPacket>>()
    val gameEvents = LinkedHashMap<ClientboundGameEventPacket.Type, Retained<ClientboundGameEventPacket>>()
    var time: ClientboundSetTimePacket? = null
    var timeUpdatedAtNanos: Long = 0L
    val singletons = LinkedHashMap<Class<*>, Retained<*>>()
    var borderInitialize: Retained<ClientboundInitializeBorderPacket>? = null
    val borderUpdates = LinkedHashMap<Class<*>, Retained<*>>()
    var dimension: Int = 0
    var dimensionKey: String = "minecraft:overworld"
    var minSectionY: Int = -4
    var sectionCount: Int = 24
    var raining: Boolean = false
    var containers: PalettedContainerFactory? = null

    fun apply(source: CapturedPacket, packet: Packet<*>, nanos: Long): Boolean {
        when (packet) {
            is ClientboundLevelChunkWithLightPacket -> chunks[ChunkPos.pack(packet.x, packet.z)] = ShadowChunk(Retained.of(packet, source), nanos)
            is ClientboundForgetLevelChunkPacket -> Unit
            is ClientboundBlockUpdatePacket -> chunkAt(packet.pos.x shr 4, packet.pos.z shr 4)?.update(Retained.of(packet, source))
            is ClientboundSectionBlocksUpdatePacket -> {
                val section = (packet as SectionBlocksUpdateAccessor).afterimage_sectionPos()
                chunkAt(section.x(), section.z())?.update(Retained.of(packet, source))
            }

            is ClientboundBlockEntityDataPacket -> chunkAt(packet.pos.x shr 4, packet.pos.z shr 4)?.let { it.blockEntities[packet.pos] = Retained.of(packet, source) }
            is ClientboundLightUpdatePacket -> chunkAt(packet.x, packet.z)?.update(Retained.of(packet, source))
            is ClientboundChunksBiomesPacket -> for (data in packet.chunkBiomeData()) {
                val chunk = chunkAt(data.pos().x(), data.pos().z()) ?: continue
                chunk.update(Retained(ClientboundChunksBiomesPacket(listOf(data)), Retained.hashOf(data.buffer(), data.pos().pack())))
            }

            is ClientboundBossEventPacket -> applyBossBar(source, packet)
            is ClientboundMapItemDataPacket -> maps.getOrPut(packet.mapId().id()) { ArrayList() }.add(Retained.of(packet, source))
            is ClientboundTrackedWaypointPacket -> {
                val id = packet.waypoint().id()
                if (packet.operation() == ClientboundTrackedWaypointPacket.Operation.UNTRACK) waypoints.remove(id) else waypoints[id] = Retained.of(packet, source)
            }

            is ClientboundResourcePackPushPacket -> resourcePacks[packet.id()] = Retained.of(packet, source)
            is ClientboundResourcePackPopPacket -> {
                val id = packet.id().orElse(null)
                if (id == null) resourcePacks.clear() else resourcePacks.remove(id)
            }

            is ClientboundGameEventPacket -> when (packet.event) {
                ClientboundGameEventPacket.START_RAINING -> raining = true
                ClientboundGameEventPacket.STOP_RAINING -> raining = false
                else -> gameEvents[packet.event] = Retained.of(packet, source)
            }

            is ClientboundSetTimePacket -> {
                time = packet
                timeUpdatedAtNanos = nanos
            }

            is ClientboundSetDefaultSpawnPositionPacket, is ClientboundChangeDifficultyPacket, is ClientboundSetChunkCacheCenterPacket,
            is ClientboundSetChunkCacheRadiusPacket, is ClientboundSetSimulationDistancePacket, is ClientboundUpdateTagsPacket,
            is ClientboundTickingStatePacket, is ClientboundGameRuleValuesPacket,
                -> singletons[packet.javaClass] = Retained.of(packet, source)

            is ClientboundInitializeBorderPacket -> {
                borderInitialize = Retained.of(packet, source)
                borderUpdates.clear()
            }

            is ClientboundSetBorderCenterPacket, is ClientboundSetBorderSizePacket, is ClientboundSetBorderLerpSizePacket,
            is ClientboundSetBorderWarningDelayPacket, is ClientboundSetBorderWarningDistancePacket,
                -> borderUpdates[packet.javaClass] = Retained.of(packet, source)

            else -> return false
        }
        return true
    }

    private fun applyBossBar(source: CapturedPacket, packet: ClientboundBossEventPacket) {
        val id = (packet as BossEventAccessor).afterimage_id()
        val existing = bossBars[id]
        val retained: Retained<ClientboundBossEventPacket> = Retained(packet, Retained.hashOf(source))
        packet.dispatch(object : ClientboundBossEventPacket.Handler {
            override fun add(
                id: UUID,
                name: Component,
                progress: Float,
                color: BossEvent.BossBarColor,
                overlay: BossEvent.BossBarOverlay,
                darkenScreen: Boolean,
                playMusic: Boolean,
                createWorldFog: Boolean,
            ) {
                bossBars[id] = arrayListOf(retained)
            }

            override fun remove(id: UUID) {
                bossBars.remove(id)
            }
        })
        if (bossBars[id] === existing && existing != null) existing.add(retained)
    }

    fun chunkAt(chunkX: Int, chunkZ: Int): ShadowChunk? = chunks[ChunkPos.pack(chunkX, chunkZ)]

    fun blockState(x: Int, y: Int, z: Int): Int {
        val factory = containers ?: return 0
        val chunk = chunkAt(x shr 4, z shr 4) ?: return 0
        return chunk.blockState(x, y, z, factory, minSectionY, sectionCount)
    }

    fun setBlockState(x: Int, y: Int, z: Int, stateId: Int) {
        val factory = containers ?: return
        val chunk = chunkAt(x shr 4, z shr 4) ?: return
        chunk.blockState(x, y, z, factory, minSectionY, sectionCount)
        chunk.setBlockState(x, y, z, Block.stateById(stateId))
    }

    fun clearDimension() {
        chunks.clear()
        raining = false
        gameEvents.remove(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE)
        gameEvents.remove(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE)
        borderInitialize = null
        borderUpdates.clear()
        waypoints.clear()
    }

    fun clear() {
        clearDimension()
        bossBars.clear()
        maps.clear()
        resourcePacks.clear()
        gameEvents.clear()
        time = null
        singletons.clear()
    }

    fun metaPackets(out: MutableList<Packet<*>>) {
        for (kind in SINGLETON_ORDER) singletons[kind]?.let { out += it.packet }
        if (raining) out += ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0f)
        for (event in gameEvents.values) if (event.packet.event != ClientboundGameEventPacket.CHANGE_GAME_MODE) out += event.packet
        borderInitialize?.let { out += it.packet }
        for (update in borderUpdates.values) out += update.packet
    }

    fun chunkPackets(out: MutableList<Packet<*>>) {
        for (chunk in chunks.values.sortedWith(compareBy({ it.chunk.packet.x }, { it.chunk.packet.z }))) chunk.emit(out)
    }

    fun extraPackets(out: MutableList<Packet<*>>) {
        for (bar in bossBars.values) for (packet in bar) out += packet.packet
        for (map in maps.values) for (packet in map) out += packet.packet
        for (waypoint in waypoints.values) out += waypoint.packet
        for (pack in resourcePacks.values) out += pack.packet
    }

    companion object {
        val SINGLETON_ORDER: List<Class<*>> = listOf(
            ClientboundUpdateTagsPacket::class.java,
            ClientboundChangeDifficultyPacket::class.java,
            ClientboundGameRuleValuesPacket::class.java,
            ClientboundTickingStatePacket::class.java,
            ClientboundSetChunkCacheRadiusPacket::class.java,
            ClientboundSetSimulationDistancePacket::class.java,
            ClientboundSetChunkCacheCenterPacket::class.java,
            ClientboundSetDefaultSpawnPositionPacket::class.java,
        )
    }
}
