package gg.sona.afterimage.mc

import com.mojang.authlib.GameProfile
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.mc.mixin.ChatGuiAccessor
import gg.sona.afterimage.mc.mixin.GameGuiAccessor
import gg.sona.afterimage.mc.mixin.LivingEntityAccessor
import gg.sona.afterimage.mc.mixin.RemoteClientPlayerAccessor
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.protocol.*
import gg.sona.afterimage.replay.consumer.DeliveryMode
import gg.sona.afterimage.replay.consumer.ReplayConsumer
import gg.sona.afterimage.replay.consumer.ResetReason
import gg.sona.afterimage.protocol47.perspective.TransientPackets
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.ReferenceCountUtil
import net.minecraft.client.ClientPlayerInteractionManager
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.living.player.RemoteClientPlayerEntity
import net.minecraft.client.network.handler.ClientPlayNetworkHandler
import net.minecraft.entity.living.LivingEntity
import net.minecraft.network.*
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import org.apache.logging.log4j.LogManager
import java.util.*

class VirtualConnection(private val minecraft: Minecraft, private val profile: GameProfile) : ReplayConsumer {

    private class DropOutbound : ChannelOutboundHandlerAdapter() {
        override fun write(context: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
            ReferenceCountUtil.release(message)
            promise.setSuccess()
        }
    }

    private var channel: EmbeddedChannel? = null
    private var connection: Connection? = null

    var delivered: Long = 0L
        private set

    var failures: Long = 0L
        private set

    val isOpen: Boolean get() = channel != null

    val handler: ClientPlayNetworkHandler? get() = connection?.listener as? ClientPlayNetworkHandler

    fun open() {
        close()
        val connection = Connection(PacketFlow.CLIENTBOUND)
        val placeholder = DropOutbound()
        val channel = EmbeddedChannel(placeholder)
        channel.pipeline().remove(placeholder)
        channel.pipeline().removeLast()
        channel.pipeline().addLast(DROP_OUTBOUND, DropOutbound())
        channel.pipeline().addLast(DECODER, PacketDecoder(PacketFlow.CLIENTBOUND))
        channel.pipeline().addLast(ENCODER, PacketEncoder(PacketFlow.SERVERBOUND))
        channel.pipeline().addLast(PACKET_HANDLER, connection)
        channel.pipeline().fireChannelActive()
        connection.setProtocol(NetworkProtocol.PLAY)
        AfterimageHooks.registerReplayConnection(connection)
        val handler = AfterimageHooks.asReplayConnection { ClientPlayNetworkHandler(minecraft, null, connection, profile) }
        connection.listener = handler
        if (minecraft.interactionManager != null) minecraft.interactionManager =
            ClientPlayerInteractionManager(minecraft, handler)
        this.connection = connection
        this.channel = channel
        delivered = 0L
        failures = 0L
    }

    override fun onReset(reason: ResetReason) {
        if (reason == ResetReason.CLOSE) close() else open()
    }

    var timeOverride: () -> Long? = { null }
    var targetFace: () -> Int = { -1 }
    var profileLookup: (UUID) -> PlayerListEntry? = { null }

    @Volatile
    var lastDelivered: CapturedPacket? = null
        private set

    private fun ensureSpawnProfile(packet: CapturedPacket): UUID? {
        val spawn = PacketCodec.decode(packet) as? SpawnPlayer ?: return null
        val current = handler ?: return null
        if (current.getOnlinePlayer(spawn.uuid) != null) return null
        val entry = profileLookup(spawn.uuid) ?: PlayerListEntry(
            spawn.uuid,
            spawn.uuid.toString().take(16),
            emptyList(),
            0,
            0,
            null
        )
        deliverDecoded(PlayerListItem(PlayerListItem.ADD_PLAYER, listOf(entry)), packet.timestampNanos)
        return spawn.uuid
    }

    override fun onPacket(packet: CapturedPacket, mode: DeliveryMode) {
        val channel = channel ?: return
        if (packet.packetId == AfterimageInternal.OVERLAY_RESET) {
            (PacketCodec.decode(packet) as? OverlayReset)?.let { applyOverlayReset(it) }
            return
        }
        if (packet.packetId == ClientboundPlay.CHUNK_DATA && (PacketCodec.decode(packet) as? ChunkData)?.isUnload == true) return
        if (mode == DeliveryMode.SEEK && TransientPackets.isTransient(packet)) return
        if (packet.packetId == AfterimageInternal.LOCAL_BLOCK_BREAK) {
            (PacketCodec.decode(packet) as? LocalBlockBreak)?.let { addMiningParticles(it, packet.timestampNanos) }
            return
        }
        var payload = packet.payload
        if (packet.packetId == ClientboundPlay.TEAMS || packet.packetId == ClientboundPlay.SCOREBOARD_OBJECTIVE || packet.packetId == ClientboundPlay.UPDATE_SCORE) {
            payload = sanitizeScoreboard(packet) ?: return
        }
        if (packet.packetId == ClientboundPlay.TIME_UPDATE) {
            val override = timeOverride()
            if (override != null) {
                val update = PacketCodec.decode(packet) as? TimeUpdate
                if (update != null) payload =
                    PacketCodec.encode(TimeUpdate(update.worldAge, override), packet.timestampNanos).payload
            }
        }
        val ghostProfile = if (packet.packetId == ClientboundPlay.SPAWN_PLAYER) ensureSpawnProfile(packet) else null
        val buffer = Unpooled.buffer(payload.size + 5)
        writeVarInt(buffer, packet.packetId)
        buffer.writeBytes(payload)
        lastDelivered = packet
        try {
            AfterimageHooks.reseedForPacket(packet.timestampNanos)
            channel.pipeline().fireChannelRead(buffer)
            delivered++
        } catch (error: Throwable) {
            failures++
            if (failures <= LOG_FAILURES) LOGGER.error("Afterimage replay could not deliver {}", packet, error)
        }
        if (ghostProfile != null) {
            deliverDecoded(
                PlayerListItem(
                    PlayerListItem.REMOVE_PLAYER,
                    listOf(PlayerListEntry(ghostProfile, "", emptyList(), 0, 0, null))
                ), packet.timestampNanos
            )
        }
    }

    private fun sanitizeScoreboard(packet: CapturedPacket): ByteArray? {
        val scoreboard = minecraft.world?.scoreboard ?: return packet.payload
        val decoded = PacketCodec.decode(packet) ?: return packet.payload
        val replacement: ClientboundPacket? = when (decoded) {
            is Teams -> {
                val existing = scoreboard.getTeam(decoded.name)
                when (decoded.mode) {
                    Teams.CREATE -> if (existing == null) decoded else {
                        val members = decoded.players.filter { scoreboard.getTeamOfMember(it)?.name != decoded.name }
                        pending += decoded.copy(mode = Teams.UPDATE, players = emptyList())
                        if (members.isEmpty()) null else decoded.copy(mode = Teams.ADD_PLAYERS, players = members)
                    }

                    Teams.REMOVE -> if (existing == null) null else decoded
                    Teams.UPDATE -> if (existing == null) decoded.copy(mode = Teams.CREATE) else decoded
                    Teams.ADD_PLAYERS -> if (existing == null) null else decoded
                    Teams.REMOVE_PLAYERS -> {
                        val members = if (existing == null) emptyList() else decoded.players.filter {
                            scoreboard.getTeamOfMember(it)?.name == decoded.name
                        }
                        if (members.isEmpty()) null else decoded.copy(players = members)
                    }

                    else -> decoded
                }
            }

            is ScoreboardObjective -> {
                val existing = scoreboard.getObjective(decoded.name)
                when (decoded.mode) {
                    ScoreboardObjective.CREATE -> if (existing == null) decoded else decoded.copy(mode = ScoreboardObjective.UPDATE)
                    ScoreboardObjective.REMOVE -> if (existing == null) null else decoded
                    ScoreboardObjective.UPDATE -> if (existing == null) decoded.copy(mode = ScoreboardObjective.CREATE) else decoded
                    else -> decoded
                }
            }

            is UpdateScore -> {
                if (decoded.action == UpdateScore.CHANGE && scoreboard.getObjective(decoded.objective) == null) null else decoded
            }

            else -> decoded as? ClientboundPacket
        }
        val flushed = pending.toList()
        pending.clear()
        for (extra in flushed) deliverDecoded(extra, packet.timestampNanos)
        if (replacement == null) return null
        return if (replacement === decoded) packet.payload else PacketCodec.encode(
            replacement,
            packet.timestampNanos
        ).payload
    }

    private val pending = ArrayList<ClientboundPacket>()

    private fun deliverDecoded(decoded: ClientboundPacket, nanos: Long) {
        val channel = channel ?: return
        val encoded = PacketCodec.encode(decoded, nanos)
        val buffer = Unpooled.buffer(encoded.payload.size + 5)
        writeVarInt(buffer, encoded.packetId)
        buffer.writeBytes(encoded.payload)
        runCatching {
            channel.pipeline().fireChannelRead(buffer)
        }.onFailure { LOGGER.warn("Afterimage replay could not deliver {}", decoded, it) }
    }

    fun replayEvent(packet: CapturedPacket) = onPacket(packet, DeliveryMode.LIVE)

    fun syncOverlays(packets: List<PlayPacket>, nanos: Long) {
        for (packet in packets) {
            when (packet) {
                is OverlayReset -> applyOverlayReset(packet)
                is ClientboundPacket -> deliverDecoded(packet, nanos)
                else -> Unit
            }
        }
    }

    private fun applyOverlayReset(reset: OverlayReset) {
        if (reset.resetsChat) resetChat(reset)
        if (reset.resetsTitle) resetTitle(reset.titleAgeNanos)
        if (reset.resetsActionBar) resetActionBar(reset.actionBarAgeNanos)
    }

    private fun addMiningParticles(packet: LocalBlockBreak, nanos: Long) {
        if (minecraft.world == null) return
        AfterimageHooks.reseedForPacket(nanos)
        val face = targetFace().takeIf { it in 0..5 } ?: UP_FACE
        minecraft.particleManager.addBlockMiningParticles(BlockPos.fromLong(packet.position), Direction.byId(face))
    }

    private fun resetChat(reset: OverlayReset) {
        val chat = minecraft.gui.chat
        chat.clear()
        val now = (minecraft.gui as GameGuiAccessor).`afterimage$ticks`()
        for (line in reset.chat.sortedByDescending { it.ageNanos }) {
            val text = runCatching { Text.Serializer.fromJson(line.json) }.getOrNull() ?: continue
            val age = (line.ageNanos / Nanos.PER_TICK).toInt().coerceIn(0, 200)
            (chat as ChatGuiAccessor).`afterimage$addMessage`(text, 0, now - age, false)
        }
    }

    private fun resetTitle(ageNanos: Long) {
        val gui = minecraft.gui as GameGuiAccessor
        if (ageNanos < 0) {
            gui.`afterimage$setTitleTime`(0)
            return
        }
        val total = gui.`afterimage$titleFadeInTime`() + gui.`afterimage$titleDuration`() + gui.`afterimage$titleFadeOutTime`()
        val remaining = total - (ageNanos / Nanos.PER_TICK).toInt()
        gui.`afterimage$setTitleTime`(remaining.coerceAtLeast(0))
    }

    private fun resetActionBar(ageNanos: Long) {
        val gui = minecraft.gui as GameGuiAccessor
        val remaining = if (ageNanos < 0) 0 else ACTION_BAR_TICKS - (ageNanos / Nanos.PER_TICK).toInt()
        gui.`afterimage$setOverlayMessageCooldown`(remaining.coerceAtLeast(0))
    }

    override fun onSettled(positionNanos: Long, mode: DeliveryMode) {
        if (mode == DeliveryMode.SEEK) snapEntities()
    }

    private fun snapEntities() {
        val world = minecraft.world ?: return
        for (entity in world.entities.toList()) {
            when (entity) {
                is RemoteClientPlayerEntity -> {
                    val accessor = entity as RemoteClientPlayerAccessor
                    if (accessor.`afterimage$remoteLerpSteps`() > 0) {
                        entity.setPositionAndAngles(
                            accessor.`afterimage$remoteLerpX`(),
                            accessor.`afterimage$remoteLerpY`(),
                            accessor.`afterimage$remoteLerpZ`(),
                            accessor.`afterimage$remoteLerpYaw`().toFloat(),
                            accessor.`afterimage$remoteLerpPitch`().toFloat()
                        )
                        accessor.`afterimage$remoteLerpSteps`(0)
                    }
                }

                is LivingEntity -> {
                    val accessor = entity as LivingEntityAccessor
                    if (accessor.`afterimage$lerpSteps`() > 0) {
                        entity.setPositionAndAngles(
                            accessor.`afterimage$lerpX`(),
                            accessor.`afterimage$lerpY`(),
                            accessor.`afterimage$lerpZ`(),
                            accessor.`afterimage$lerpYaw`().toFloat(),
                            accessor.`afterimage$lerpPitch`().toFloat()
                        )
                        accessor.`afterimage$lerpSteps`(0)
                    }
                }
            }
            entity.prevX = entity.x
            entity.prevY = entity.y
            entity.prevZ = entity.z
            entity.lastYaw = entity.yaw
            entity.lastPitch = entity.pitch
            if (entity is LivingEntity) {
                entity.lastHeadYaw = entity.headYaw
                entity.lastBodyYaw = entity.bodyYaw
            }
        }
    }

    fun tick() {
        connection?.tick()
    }

    fun close() {
        val channel = channel ?: return
        this.channel = null
        this.connection = null
        runCatching {
            channel.pipeline().fireChannelInactive()
            channel.close()
        }
    }

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
        private const val UP_FACE = 1
        private const val DROP_OUTBOUND = "afterimage_drop_outbound"
        private const val DECODER = "decoder"
        private const val ENCODER = "encoder"
        private const val PACKET_HANDLER = "packet_handler"
        private val LOGGER = LogManager.getLogger("Afterimage")

        val ANONYMOUS = GameProfile(UUID(0L, 0L), "Afterimage")
    }
}
