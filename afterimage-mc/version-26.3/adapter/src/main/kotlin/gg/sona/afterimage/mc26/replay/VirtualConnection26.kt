package gg.sona.afterimage.mc26.replay

import com.mojang.authlib.GameProfile
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.mc26.AfterimageHooks26
import gg.sona.afterimage.mc26.mixin.ChatComponentInvoker
import gg.sona.afterimage.mc26.mixin.HudAccessor
import gg.sona.afterimage.mc26.mixin.SetPlayerTeamAccessor
import gg.sona.afterimage.mc26.mixin.SetPlayerTeamInvoker
import gg.sona.afterimage.mc26.net.PacketIds26
import gg.sona.afterimage.mc26.protocol.Protocol26
import gg.sona.afterimage.mc26.state.Components26
import gg.sona.afterimage.mc26.state.ShadowOverlays26
import gg.sona.afterimage.mc26.state.ShadowScoreboard26
import gg.sona.afterimage.mc26.state.ShadowTeam26
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PackedPosition
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.protocol.AfterimageInternal
import gg.sona.afterimage.protocol.InternalCodec
import gg.sona.afterimage.protocol.LocalBlockBreak
import gg.sona.afterimage.protocol.LocalTarget
import gg.sona.afterimage.protocol.OverlayReset
import gg.sona.afterimage.replay.consumer.DeliveryMode
import gg.sona.afterimage.replay.consumer.ReplayConsumer
import gg.sona.afterimage.replay.consumer.ResetReason
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.ReferenceCountUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.multiplayer.ClientRegistryLayer
import net.minecraft.client.multiplayer.CommonListenerCookie
import net.minecraft.client.multiplayer.LevelLoadTracker
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.client.multiplayer.chat.GuiMessageSource
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.network.Connection
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.UnconfiguredPipelineHandler
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.configuration.ConfigurationProtocols
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.GameProtocols
import net.minecraft.server.ServerLinks
import net.minecraft.world.clock.ClockNetworkState
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.flag.FeatureFlags
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.math.abs

class VirtualConnection26(private val minecraft: Minecraft, private val profile: GameProfile) : ReplayConsumer {

    private class DropOutbound : ChannelOutboundHandlerAdapter() {
        override fun write(context: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
            ReferenceCountUtil.release(message)
            promise.setSuccess()
        }
    }

    private class PlayCodec(val registries: RegistryAccess.Frozen, val codec: StreamCodec<ByteBuf, Packet<*>>)

    private var channel: EmbeddedChannel? = null
    private var connection: Connection? = null
    private var playCodec: PlayCodec? = null

    var delivered: Long = 0L
        private set

    var failures: Long = 0L
        private set

    var timeOverride: () -> Long? = { null }
    var chunkCenter: () -> IntArray? = { null }
    var chunkRadius: () -> Int? = { null }
    var targetFace: () -> Int = { -1 }
    var snapOnSeek: () -> Boolean = { true }

    val isOpen: Boolean get() = channel != null

    val handler: ClientPacketListener? get() = connection?.packetListener as? ClientPacketListener

    @Volatile
    var lastDelivered: CapturedPacket? = null
        private set

    fun open() {
        close()
        val connection = Connection(PacketFlow.CLIENTBOUND)
        val placeholder = DropOutbound()
        val channel = EmbeddedChannel(placeholder)
        channel.pipeline().remove(placeholder)
        channel.pipeline().addLast(DROP_OUTBOUND, DropOutbound())
        channel.pipeline().addLast(INBOUND_CONFIG, UnconfiguredPipelineHandler.Inbound())
        channel.pipeline().addLast(OUTBOUND_CONFIG, UnconfiguredPipelineHandler.Outbound())
        channel.pipeline().addLast(PACKET_HANDLER, connection)
        ReplayConnections26.register(connection)
        channel.pipeline().fireChannelActive()
        val cookie = CommonListenerCookie(
            LevelLoadTracker(),
            profile,
            minecraft.telemetryManager.createWorldSessionManager(false, null, null, UUID.randomUUID()),
            ClientRegistryLayer.createRegistryAccess().compositeAccess(),
            FeatureFlags.DEFAULT_FLAGS,
            null,
            null,
            null,
            HashMap(),
            null,
            emptyMap(),
            ServerLinks.EMPTY,
            HashMap(),
            false,
        )
        connection.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, ClientConfigurationPacketListenerImpl(minecraft, connection, cookie))
        connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND)
        this.connection = connection
        this.channel = channel
        playCodec = null
        delivered = 0L
        failures = 0L
    }

    override fun onReset(reason: ResetReason) {
        if (reason == ResetReason.CLOSE) close() else open()
    }

    override fun onPacket(packet: CapturedPacket, mode: DeliveryMode) {
        val channel = channel ?: return
        if (packet.packetId == AfterimageInternal.OVERLAY_RESET) {
            (InternalCodec.decode(packet) as? OverlayReset)?.let { applyOverlayReset(it) }
            return
        }
        if (packet.direction != PacketDirection.CLIENTBOUND) {
            if (packet.packetId == AfterimageInternal.LOCAL_BLOCK_BREAK && mode != DeliveryMode.SEEK) {
                (InternalCodec.decode(packet) as? LocalBlockBreak)?.let {
                    AfterimageHooks26.reseedForPacket(packet.timestampNanos)
                    addMiningParticles(it)
                }
            }
            return
        }
        if (mode == DeliveryMode.SEEK && Protocol26.INSTANCE.isTransient(packet)) return
        val wireId = PacketIds26.wireId(packet.packetId)
        if (!PacketIds26.isConfiguration(packet.packetId)) {
            if (wireId == PacketIds26.BUNDLE_DELIMITER || wireId == PacketIds26.DELETE_CHAT) return
        }
        var payload = packet.payload
        if (!PacketIds26.isConfiguration(packet.packetId)) {
            when (wireId) {
                PacketIds26.FORGET_LEVEL_CHUNK -> if (chunkCenter() != null) return
                PacketIds26.LEVEL_CHUNK_WITH_LIGHT -> chunkCenter()?.let { center ->
                    val radius = chunkRadius() ?: return@let
                    if (payload.size < 8) return@let
                    val x = readInt(payload, 0)
                    val z = readInt(payload, 4)
                    if (maxOf(abs(x - center[0]), abs(z - center[1])) > radius + 1) return
                }
                PacketIds26.SET_CHUNK_CACHE_CENTER -> chunkCenter()?.let { center -> payload = encode(ClientboundSetChunkCacheCenterPacket(center[0], center[1])) ?: payload }
                PacketIds26.SET_CHUNK_CACHE_RADIUS -> chunkRadius()?.let { radius -> payload = encode(ClientboundSetChunkCacheRadiusPacket(radius)) ?: payload }
                PacketIds26.SET_OBJECTIVE, PacketIds26.SET_PLAYER_TEAM -> payload = sanitizeScoreboard(packet) ?: return
                PacketIds26.PLAYER_CHAT -> payload = rewriteChat(packet) ?: return
                PacketIds26.SET_TIME -> payload = rewriteTime(packet) ?: payload
            }
        }
        val buffer = Unpooled.buffer(payload.size + 5)
        writeVarInt(buffer, wireId)
        buffer.writeBytes(payload)
        lastDelivered = packet
        try {
            AfterimageHooks26.reseedForPacket(packet.timestampNanos)
            channel.pipeline().fireChannelRead(buffer)
            delivered++
        } catch (error: Throwable) {
            failures++
            if (failures <= LOG_FAILURES) LOGGER.error("Afterimage replay could not deliver {}", packet, error)
        }
    }

    override fun onSettled(positionNanos: Long, mode: DeliveryMode) {
        if (mode == DeliveryMode.SEEK && snapOnSeek()) snapEntities()
    }

    fun replayEvent(packet: CapturedPacket) = onPacket(packet, DeliveryMode.LIVE)

    fun tick() {
        connection?.tick()
    }

    fun close() {
        val channel = channel ?: return
        val connection = this.connection
        this.channel = null
        this.connection = null
        playCodec = null
        runCatching {
            channel.pipeline().fireChannelInactive()
            channel.close()
        }
        connection?.let { ReplayConnections26.unregister(it) }
    }

    private fun codec(): PlayCodec? {
        val registries = handler?.registryAccess() ?: return null
        playCodec?.takeIf { it.registries === registries }?.let { return it }
        @Suppress("UNCHECKED_CAST")
        val codec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries)).codec() as StreamCodec<ByteBuf, Packet<*>>
        return PlayCodec(registries, codec).also { playCodec = it }
    }

    private fun decode(packet: CapturedPacket): Packet<*>? {
        val codec = codec() ?: return null
        val buffer = Unpooled.buffer(packet.payload.size + 5)
        writeVarInt(buffer, PacketIds26.wireId(packet.packetId))
        buffer.writeBytes(packet.payload)
        return try {
            codec.codec.decode(buffer)
        } catch (error: Exception) {
            null
        } finally {
            buffer.release()
        }
    }

    private fun encode(packet: Packet<*>): ByteArray? {
        val codec = codec() ?: return null
        val buffer = Unpooled.buffer(256)
        try {
            codec.codec.encode(buffer, packet)
            var index = 0
            while (buffer.getByte(index).toInt() and 0x80 != 0) index++
            index++
            val bytes = ByteArray(buffer.readableBytes() - index)
            buffer.getBytes(index, bytes)
            return bytes
        } catch (error: Exception) {
            LOGGER.warn("Afterimage replay could not encode {}", packet.javaClass.simpleName, error)
            return null
        } finally {
            buffer.release()
        }
    }

    private fun sanitizeScoreboard(packet: CapturedPacket): ByteArray? {
        val scoreboard = handler?.scoreboard() ?: return packet.payload
        val decoded = decode(packet) ?: return packet.payload
        val replacement: Packet<*>? = when (decoded) {
            is ClientboundSetObjectivePacket -> {
                val existing = scoreboard.getObjective(decoded.objectiveName)
                when (decoded.method) {
                    ClientboundSetObjectivePacket.METHOD_ADD -> if (existing == null) decoded else ClientboundSetObjectivePacket(ShadowScoreboard26.dummyObjective(decoded), ClientboundSetObjectivePacket.METHOD_CHANGE)
                    ClientboundSetObjectivePacket.METHOD_REMOVE -> if (existing == null) null else decoded
                    ClientboundSetObjectivePacket.METHOD_CHANGE -> if (existing == null) ClientboundSetObjectivePacket(ShadowScoreboard26.dummyObjective(decoded), ClientboundSetObjectivePacket.METHOD_ADD) else decoded
                    else -> decoded
                }
            }

            is ClientboundSetPlayerTeamPacket -> {
                val existing = scoreboard.getPlayerTeam(decoded.name)
                when ((decoded as SetPlayerTeamAccessor).afterimage_method()) {
                    ShadowTeam26.METHOD_ADD -> if (existing == null) decoded else {
                        val members = decoded.players.filter { scoreboard.getPlayersTeam(it)?.name != decoded.name }
                        deliverDecoded(SetPlayerTeamInvoker.afterimage_create(decoded.name, ShadowTeam26.METHOD_CHANGE, decoded.parameters, emptyList()))
                        if (members.isEmpty()) null else SetPlayerTeamInvoker.afterimage_create(decoded.name, ShadowTeam26.METHOD_JOIN, Optional.empty(), members)
                    }

                    ShadowTeam26.METHOD_CHANGE -> if (existing == null) SetPlayerTeamInvoker.afterimage_create(decoded.name, ShadowTeam26.METHOD_ADD, decoded.parameters, emptyList()) else decoded
                    ShadowTeam26.METHOD_LEAVE -> {
                        val members = if (existing == null) emptyList() else decoded.players.filter { scoreboard.getPlayersTeam(it)?.name == decoded.name }
                        if (members.isEmpty()) null else SetPlayerTeamInvoker.afterimage_create(decoded.name, ShadowTeam26.METHOD_LEAVE, Optional.empty(), members)
                    }

                    else -> decoded
                }
            }

            else -> decoded
        }
        if (replacement == null) return null
        return if (replacement === decoded) packet.payload else encode(replacement)
    }

    private fun rewriteChat(packet: CapturedPacket): ByteArray? {
        val decoded = decode(packet) as? ClientboundPlayerChatPacket ?: return null
        return encode(ClientboundSystemChatPacket(ShadowOverlays26.decorate(decoded), false))
    }

    private fun rewriteTime(packet: CapturedPacket): ByteArray? {
        val override = timeOverride() ?: return null
        val decoded = decode(packet) as? ClientboundSetTimePacket ?: return null
        val updates = decoded.clockUpdates().mapValues { (_, state) ->
            val aligned = state.totalTicks() - Math.floorMod(state.totalTicks(), 24000L) + override
            ClockNetworkState(aligned, state.partialTick(), state.rate())
        }
        return encode(ClientboundSetTimePacket(decoded.gameTime(), updates))
    }

    private fun deliverDecoded(packet: Packet<*>) {
        val channel = channel ?: return
        val payload = encode(packet) ?: return
        val buffer = Unpooled.buffer(payload.size + 5)
        writeVarInt(buffer, PacketIds26.clientbound(packet.type()))
        buffer.writeBytes(payload)
        runCatching { channel.pipeline().fireChannelRead(buffer) }.onFailure { LOGGER.warn("Afterimage replay could not deliver {}", packet, it) }
    }

    private fun applyOverlayReset(reset: OverlayReset) {
        if (reset.resetsChat) resetChat(reset)
        if (reset.resetsTitle) resetTitle(reset.titleAgeNanos)
        if (reset.resetsActionBar) resetActionBar(reset.actionBarAgeNanos)
    }

    private fun resetChat(reset: OverlayReset) {
        val hud = minecraft.gui.hud
        val chat = hud.chat
        chat.clearMessages(false)
        val now = hud.guiTicks
        val registries = handler?.registryAccess()
        for (line in reset.chat.sortedByDescending { it.ageNanos }) {
            val text = Components26.parse(line.json, registries) ?: continue
            val age = (line.ageNanos / Nanos.PER_TICK).toInt().coerceIn(0, 200)
            val message = GuiMessage(now - age, text, null, GuiMessageSource.SYSTEM_SERVER, null)
            (chat as ChatComponentInvoker).afterimage_addToDisplayQueue(message)
            (chat as ChatComponentInvoker).afterimage_addToQueue(message)
        }
    }

    private fun resetTitle(ageNanos: Long) {
        val hud = minecraft.gui.hud as HudAccessor
        if (ageNanos < 0) {
            hud.afterimage_setTitleTime(0)
            return
        }
        val total = hud.afterimage_titleFadeInTime() + hud.afterimage_titleStayTime() + hud.afterimage_titleFadeOutTime()
        hud.afterimage_setTitleTime((total - (ageNanos / Nanos.PER_TICK).toInt()).coerceAtLeast(0))
    }

    private fun resetActionBar(ageNanos: Long) {
        val hud = minecraft.gui.hud as HudAccessor
        val remaining = if (ageNanos < 0) 0 else ACTION_BAR_TICKS - (ageNanos / Nanos.PER_TICK).toInt()
        hud.afterimage_setOverlayMessageTime(remaining.coerceAtLeast(0))
    }

    private fun addMiningParticles(packet: LocalBlockBreak) {
        val level = minecraft.level ?: return
        if (packet.stage < 0) return
        val face = targetFace().takeIf { it in 0..5 } ?: Direction.UP.get3DDataValue()
        val pos = BlockPos(PackedPosition.x(packet.position), PackedPosition.y(packet.position), PackedPosition.z(packet.position))
        level.addBreakingBlockEffects(pos, Direction.from3DDataValue(face), false)
    }

    private fun snapEntities() {
        val level = minecraft.level ?: return
        for (entity in level.entitiesForRendering().toList()) {
            if (entity === minecraft.player) continue
            val interpolation = entity.interpolation
            val target = interpolation.target()
            if (target != null) {
                entity.setPos(target.position())
                entity.yRot = target.yRot()
                entity.xRot = target.xRot()
                interpolation.cancel()
            }
            entity.setOldPosAndRot()
            if (entity is LivingEntity) {
                entity.yHeadRotO = entity.yHeadRot
                entity.yBodyRotO = entity.yBodyRot
            }
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF shl 24) or (bytes[offset + 1].toInt() and 0xFF shl 16) or (bytes[offset + 2].toInt() and 0xFF shl 8) or (bytes[offset + 3].toInt() and 0xFF)

    private fun writeVarInt(buffer: ByteBuf, value: Int) {
        var remaining = value
        while (remaining and -0x80 != 0) {
            buffer.writeByte((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        buffer.writeByte(remaining)
    }

    companion object {
        private const val LOG_FAILURES = 8L
        private const val ACTION_BAR_TICKS = 60
        private const val DROP_OUTBOUND = "afterimage_drop_outbound"
        private const val INBOUND_CONFIG = "inbound_config"
        private const val OUTBOUND_CONFIG = "outbound_config"
        private const val PACKET_HANDLER = "packet_handler"
        private val LOGGER = LoggerFactory.getLogger("Afterimage")

        val ANONYMOUS = GameProfile(UUID(0L, 0L), "Afterimage")
    }
}
