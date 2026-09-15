package gg.sona.afterimage.mc26.replay

import com.mojang.authlib.GameProfile
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.mc26.MinecraftPlatform26
import gg.sona.afterimage.mc26.state.ShadowClient26
import gg.sona.afterimage.mc26.ui.ExportScreen26
import gg.sona.afterimage.mc26.ui.ScreenGuard26
import gg.sona.afterimage.mc26.ui.WorkspaceScreen26
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.replay.consumer.DeliveryMode
import gg.sona.afterimage.replay.consumer.ReplayConsumer
import gg.sona.afterimage.replay.consumer.ResetReason
import gg.sona.afterimage.replay.session.ReplayListener
import gg.sona.afterimage.replay.session.ReplayOptions
import gg.sona.afterimage.replay.session.ReplaySession
import gg.sona.afterimage.replay.source.FileReplaySource
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket
import net.minecraft.util.Mth
import net.minecraft.client.gui.screens.ProgressScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path

class ReplayController26(private val platform: MinecraftPlatform26, val camera: CameraDriver26) {
    private val logger = LoggerFactory.getLogger("Afterimage")
    private val minecraft = platform.minecraft
    var session: ReplaySession? = null
        private set
    var path: Path? = null
        private set
    var lastError: String? = null
        private set
    var timeOverride: () -> Long? = { null }
    var onOpened: (Path) -> Unit = {}
    var onWorldReady: (Boolean) -> Unit = {}
    var onWorldTick: (Long) -> Unit = {}
    var onSeek: (Long) -> Unit = {}
    var onClosed: () -> Unit = {}
    var isWorkspaceOpen: () -> Boolean = { false }
    private var mirror: VirtualConnection26? = null
    var seekRestorer: SeekRestorer26? = null
    private var lastFrameNanos = Long.MIN_VALUE
    private var lastPositionNanos = Long.MIN_VALUE
    private var lastCameraFrameNanos = Long.MIN_VALUE
    private var userViewDistance = -1
    private var lastViewDistanceCheckNanos = 0L
    var matchRecordedViewDistance: Boolean = true
    private var awaitingWorld = false
    private var reopenWorkspace = true
    var applyServerPacks: Boolean = true

    @Volatile
    var syncChunkFrames: Int = 0

    private val resetWatcher = object : ReplayConsumer {
        override fun onReset(reason: ResetReason) {
            if (reason != ResetReason.SEEK) return
            awaitingWorld = true
            reopenWorkspace = isWorkspaceOpen()
        }

        override fun onPacket(packet: CapturedPacket, mode: DeliveryMode) {
            if (packet.direction != PacketDirection.CLIENTBOUND) return
            val shadow = session?.shadow26 ?: return
            val decoded = shadow.lastDecoded?.takeIf { it.first === packet }?.second as? ClientboundResourcePackPushPacket ?: return
            applyServerPack(decoded)
        }
    }

    val isReplaying: Boolean get() = session != null

    fun onFrameRendered() {
        if (syncChunkFrames > 0) syncChunkFrames--
        val replay = session ?: return
        if (replay.seeks == reportedSeeks) return
        reportedSeeks = replay.seeks
        val total = replay.lastSeekDurationNanos
        if (total < SLOW_SEEK_NANOS) return
        val live = total - replay.lastPrePositionNanos - replay.lastPrepareNanos
        logger.info(
            "Afterimage seek to {} ms took {} ms: reposition {} ms ({} packets), particles {} ms, live ticks {} ms",
            replay.positionNanos / Nanos.PER_MILLI, total / Nanos.PER_MILLI, replay.lastPrePositionNanos / Nanos.PER_MILLI, replay.lastDiffPackets,
            replay.lastPrepareNanos / Nanos.PER_MILLI, live / Nanos.PER_MILLI,
        )
    }

    private var reportedSeeks = 0L

    private val appliedPacks = HashSet<java.util.UUID>()
    private var packReopenFrames = 0

    private fun applyServerPack(pack: ClientboundResourcePackPushPacket) {
        if (!applyServerPacks) return
        if (!appliedPacks.add(pack.id())) return
        logger.info("Afterimage applying the recorded server resource pack {}", pack.url())
        packReopenFrames = 40
        runCatching {
            minecraft.downloadedPackSource.allowServerPacks()
            minecraft.downloadedPackSource.pushPack(pack.id(), URI.create(pack.url()).toURL(), pack.hash().takeIf { it.isNotEmpty() })
        }.onFailure { logger.warn("Afterimage could not apply the server resource pack", it) }
    }

    fun onWorldSwitch() {
        awaitingWorld = true
        reopenWorkspace = reopenWorkspace || isWorkspaceOpen()
    }

    fun open(target: Path): Boolean {
        close()
        lastError = null
        if (minecraft.level != null) {
            lastError = "Leave the current world before opening a replay"
            return false
        }
        return try {
            val source = FileReplaySource.open(target)
            val replay = ReplaySession(source, emptyList(), ReplayOptions(settleNanos = SETTLE_NANOS))
            val shadow = replay.shadow26
            val mirror = VirtualConnection26(minecraft, cameraProfile(shadow))
            mirror.timeOverride = timeOverride
            mirror.snapOnSeek = { !replay.settling }
            mirror.chunkCenter = { cameraChunk() }
            mirror.chunkRadius = { minecraft.options.effectiveRenderDistance.coerceAtLeast(2) }
            mirror.targetFace = { shadow.localPlayer.target?.face ?: -1 }
            val projection = RecorderProjection26(shadow, mirror)
            projection.recorderMissing = { id ->
                val level = minecraft.level
                level != null && level.getEntity(id)?.isRemoved != false
            }
            replay.addConsumer(projection)
            replay.addConsumer(resetWatcher)
            replay.tickHook = onWorldTick
            replay.listeners.add(object : ReplayListener {
                override fun onSettleStart(positionNanos: Long) {
                    seekRestorer?.prepare(replay, mirror, SETTLE_NANOS)
                }

                override fun onPositionChanged(positionNanos: Long, mode: DeliveryMode) {
                    if (mode == DeliveryMode.SEEK) {
                        syncChunkFrames = SYNC_CHUNK_FRAMES_AFTER_SEEK
                        onSeek(positionNanos)
                        camera.onSeeked(replay)
                        seekRestorer?.restore(replay, mirror)
                    }
                }
            })
            this.mirror = mirror
            this.session = replay
            this.path = target
            awaitingWorld = true
            reopenWorkspace = true
            replay.load()
            advanceToFirstPosition(replay, shadow)
            check(shadow.joined) { "Recording has no world data (no clientbound packets were captured)" }
            camera.attach(replay)
            lastFrameNanos = Long.MIN_VALUE
            lastPositionNanos = Long.MIN_VALUE
            disableChunkFade()
            onOpened(target)
            logger.info("Afterimage opened replay {} ({} s)", target.fileName, replay.durationNanos / Nanos.PER_SECOND)
            true
        } catch (error: Throwable) {
            lastError = error.message ?: error.javaClass.simpleName
            logger.error("Afterimage could not open replay {}", target, error)
            close()
            false
        }
    }

    private fun cameraProfile(shadow: ShadowClient26): GameProfile {
        val identity = shadow.recorderIdentity
        val uuid = identity.uuid
        val name = identity.name
        if (uuid != null && !name.isNullOrEmpty()) return GameProfile(RecorderProjection26.cameraUuid(uuid), name)
        val user = minecraft.user
        return GameProfile(RecorderProjection26.cameraUuid(user.profileId), user.name)
    }

    fun close() = close(leaveWorld = true)

    fun abandon() {
        if (session == null) return
        logger.warn("Afterimage replay abandoned because the client joined another world")
        close(leaveWorld = false)
    }

    private fun close(leaveWorld: Boolean) {
        val replay = session ?: return
        session = null
        path = null
        awaitingWorld = false
        restoreViewDistance()
        restoreChunkFade()
        camera.detach()
        runCatching { replay.close() }.onFailure { logger.error("Afterimage failed to close replay", it) }
        val connection = mirror
        mirror = null
        if (appliedPacks.isNotEmpty()) {
            appliedPacks.clear()
            runCatching { minecraft.downloadedPackSource.popAll() }
        }
        packReopenFrames = 0
        if (leaveWorld) {
            if (minecraft.level != null) minecraft.disconnectWithProgressScreen()
            runCatching { connection?.close() }
            if (minecraft.gui.screen() !is TitleScreen) ScreenGuard26.allow { minecraft.gui.setScreen(TitleScreen()) }
        } else {
            runCatching { connection?.close() }.onFailure { logger.warn("Afterimage could not close the replay connection", it) }
        }
        onClosed()
    }

    private fun blockingScreen(): Boolean {
        val screen = minecraft.gui.screen() ?: return false
        return screen !is WorkspaceScreen26 && screen !is ExportScreen26
    }

    fun ownsWorld(): Boolean {
        val connection = minecraft.player?.connection?.connection ?: return true
        return ReplayConnections26.isReplay(connection)
    }

    fun advancePlayback() {
        val replay = session ?: return
        val now = System.nanoTime()
        val delta = if (lastFrameNanos == Long.MIN_VALUE) 0L else (now - lastFrameNanos).coerceIn(0L, Nanos.ofMillis(250))
        lastFrameNanos = now
        if (delta > 0L) replay.advance(delta)
    }

    fun onFrame(tickDelta: Float, workspaceOpen: Boolean, viewportHovered: Boolean, keyboardFree: Boolean = true) {
        val replay = session ?: return
        if (minecraft.level != null && minecraft.player != null && !ownsWorld()) {
            abandon()
            return
        }
        if (minecraft.level == null && mirror?.isOpen == true && replay.packetsDelivered > 0 && minecraft.gui.screen() is TitleScreen) {
            close()
            return
        }
        if (packReopenFrames > 0) {
            packReopenFrames--
            if (minecraft.gui.screen() is ProgressScreen) {
                packReopenFrames = 0
                awaitingWorld = true
                reopenWorkspace = true
            }
        }
        if (awaitingWorld && minecraft.level != null && minecraft.player != null && !blockingScreen()) {
            awaitingWorld = false
            logger.debug("Afterimage replay world ready after {} packets", mirror?.delivered)
            onWorldReady(reopenWorkspace)
        }
        val now = System.nanoTime()
        applyRecordedViewDistance(replay, now)
        mirror?.let { connection ->
            if (minecraft.level != null && !awaitingWorld && minecraft.player != null) {
                val position = minecraft.gameRenderer.mainCamera().position()
                chunkWindow.update(replay, connection, position.x, position.z)
            }
        }
        val position = replay.positionNanos
        val cameraDelta = if (lastPositionNanos == Long.MIN_VALUE) 0L else (position - lastPositionNanos).coerceIn(0L, Nanos.ofMillis(250))
        lastPositionNanos = position
        val wallDelta = if (lastCameraFrameNanos == Long.MIN_VALUE) 0L else (now - lastCameraFrameNanos).coerceIn(0L, Nanos.ofMillis(250))
        lastCameraFrameNanos = now
        camera.onFrame(cameraDelta, wallDelta, tickDelta, workspaceOpen, viewportHovered, keyboardFree)
    }

    private val chunkWindow = ChunkWindow26(minecraft)

    private fun cameraChunk(): IntArray? {
        if (minecraft.level == null || awaitingWorld || minecraft.player == null) return null
        val position = minecraft.gameRenderer.mainCamera().position()
        return intArrayOf(Mth.floor(position.x) shr 4, Mth.floor(position.z) shr 4)
    }

    private var packetFailures = 0L

    fun onPacketFailure(error: Throwable) {
        packetFailures++
        if (packetFailures <= 20 || packetFailures % 500 == 0L) logger.warn(
            "Afterimage replay packet {} failed ({}): {}",
            packetFailures,
            mirror?.lastDelivered?.let { "id 0x${Integer.toHexString(it.packetId)} ${it.direction} at ${it.timestampNanos / 1_000_000} ms" } ?: "unknown packet",
            error.toString(),
        )
    }

    fun onClientTick() {
        mirror?.tick()
    }

    private fun advanceToFirstPosition(replay: ReplaySession, shadow: ShadowClient26) {
        val limit = minOf(replay.endNanos, replay.positionNanos + FIRST_POSITION_SEARCH_NANOS)
        while (!shadow.localPlayer.hasPosition && replay.positionNanos < limit) {
            replay.seek(minOf(limit, replay.positionNanos + FIRST_POSITION_STEP_NANOS), linear = true)
        }
    }

    private fun applyRecordedViewDistance(replay: ReplaySession, now: Long) {
        if (!matchRecordedViewDistance) return
        if (now - lastViewDistanceCheckNanos < Nanos.ofSeconds(1)) return
        lastViewDistanceCheckNanos = now
        val option = minecraft.options.renderDistance()
        if (userViewDistance < 0) userViewDistance = option.get()
        val shadow = replay.shadow26
        val recorded = shadow.level.singletons[ClientboundSetChunkCacheRadiusPacket::class.java]?.packet as? ClientboundSetChunkCacheRadiusPacket
        val radius = recorded?.radius ?: return
        val desired = maxOf(userViewDistance, minOf(radius, MAX_VIEW_DISTANCE))
        if (option.get() != desired) option.set(desired)
    }

    private var userChunkFade = -1.0

    private fun disableChunkFade() {
        val option = minecraft.options.chunkSectionFadeInTime()
        if (userChunkFade < 0.0) userChunkFade = option.get()
        if (option.get() != 0.0) option.set(0.0)
    }

    private var savedViewDistance = -1

    fun beforeOptionsSave() {
        if (userChunkFade >= 0.0) {
            val fade = minecraft.options.chunkSectionFadeInTime()
            if (fade.get() != userChunkFade) fade.set(userChunkFade)
        }
        if (userViewDistance >= 0) {
            val distance = minecraft.options.renderDistance()
            savedViewDistance = distance.get()
            if (distance.get() != userViewDistance) distance.set(userViewDistance)
        }
    }

    fun afterOptionsSave() {
        if (session == null) return
        if (userChunkFade >= 0.0) {
            val fade = minecraft.options.chunkSectionFadeInTime()
            if (fade.get() != 0.0) fade.set(0.0)
        }
        if (savedViewDistance >= 0) {
            val distance = minecraft.options.renderDistance()
            if (distance.get() != savedViewDistance) distance.set(savedViewDistance)
            savedViewDistance = -1
        }
    }

    private fun restoreChunkFade() {
        if (userChunkFade < 0.0) return
        val option = minecraft.options.chunkSectionFadeInTime()
        if (option.get() != userChunkFade) option.set(userChunkFade)
        userChunkFade = -1.0
    }

    private fun restoreViewDistance() {
        val option = minecraft.options.renderDistance()
        if (userViewDistance >= 0 && option.get() != userViewDistance) option.set(userViewDistance)
        userViewDistance = -1
        lastViewDistanceCheckNanos = 0L
    }

    private companion object {
        const val MAX_VIEW_DISTANCE = 32
        const val SYNC_CHUNK_FRAMES_AFTER_SEEK = 2
        val SETTLE_NANOS = Nanos.ofMillis(1500)
        val SLOW_SEEK_NANOS = Nanos.ofMillis(120)
        val FIRST_POSITION_SEARCH_NANOS = Nanos.ofSeconds(30)
        val FIRST_POSITION_STEP_NANOS = Nanos.ofMillis(100)
    }
}
