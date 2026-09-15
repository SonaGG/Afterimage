package gg.sona.afterimage.mc

import com.mojang.authlib.GameProfile
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.mc.mixin.MinecraftAccessor
import gg.sona.afterimage.mc.ui.WorkspaceScreen
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.protocol.ClientboundPlay
import gg.sona.afterimage.protocol.PacketCodec
import gg.sona.afterimage.protocol.ResourcePackSend
import gg.sona.afterimage.replay.consumer.DeliveryMode
import gg.sona.afterimage.replay.consumer.ReplayConsumer
import gg.sona.afterimage.replay.consumer.ResetReason
import gg.sona.afterimage.protocol47.perspective.ProjectionOptions
import gg.sona.afterimage.protocol47.perspective.RecorderProjection
import gg.sona.afterimage.replay.session.ReplayListener
import gg.sona.afterimage.replay.session.ReplayOptions
import gg.sona.afterimage.replay.session.ReplaySession
import gg.sona.afterimage.replay.source.FileReplaySource
import net.minecraft.client.gui.screen.ProgressScreen
import net.minecraft.client.gui.screen.TitleScreen
import org.apache.logging.log4j.LogManager
import java.nio.file.Path
import kotlin.math.abs

class ReplayController(private val platform: MinecraftPlatform, val camera: CameraDriver) {

    private val logger = LogManager.getLogger("Afterimage")
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
    var onClosed: () -> Unit = {}
    var isWorkspaceOpen: () -> Boolean = { false }

    private var mirror: VirtualConnection? = null
    var seekRestorer: SeekRestorer? = null
    private var lastFrameNanos = Long.MIN_VALUE
    private var lastPositionNanos = Long.MIN_VALUE
    private var lastCameraFrameNanos = Long.MIN_VALUE
    private var userViewDistance = -1
    private var lastViewDistanceCheckNanos = 0L
    var matchRecordedViewDistance: Boolean = true
    private var awaitingWorld = false
    private var reopenWorkspace = true

    private val resetWatcher = object : ReplayConsumer {
        override fun onReset(reason: ResetReason) {
            if (reason != ResetReason.SEEK) return
            awaitingWorld = true
            reopenWorkspace = isWorkspaceOpen()
        }

        override fun onPacket(packet: CapturedPacket, mode: DeliveryMode) {
            if (packet.direction != PacketDirection.CLIENTBOUND || packet.packetId != ClientboundPlay.RESOURCE_PACK_SEND) return
            val pack = PacketCodec.decode(packet) as? ResourcePackSend ?: return
            applyServerPack(pack.url, pack.hash)
        }
    }

    var applyServerPacks: Boolean = true

    @Volatile
    var syncChunkFrames: Int = 0

    fun onFrameRendered() {
        if (syncChunkFrames > 0) syncChunkFrames--
    }

    private var appliedPack: String? = null
    private var packReopenFrames = 0

    private fun applyServerPack(url: String, hash: String) {
        if (!applyServerPacks) return
        val key = "$hash|$url"
        if (key == appliedPack) return
        appliedPack = key
        if (url.startsWith("level://")) return
        logger.info("Afterimage applying the recorded server resource pack {}", url)
        packReopenFrames = 40
        runCatching {
            minecraft.resourcePacks.downloadServerPack(
                url,
                hash
            )
        }.onFailure { logger.warn("Afterimage could not apply the server resource pack", it) }
    }

    fun onWorldSwitch() {
        awaitingWorld = true
        reopenWorkspace = reopenWorkspace || isWorkspaceOpen()
    }

    val isReplaying: Boolean get() = session != null

    fun open(target: Path): Boolean {
        close()
        lastError = null
        if (minecraft.world != null) {
            lastError = "Leave the current world before opening a replay"
            return false
        }
        return try {
            val source = FileReplaySource.open(target)
            val replay = ReplaySession(source, emptyList(), ReplayOptions())
            val mirror = VirtualConnection(minecraft, cameraProfile(replay))
            mirror.timeOverride = timeOverride
            mirror.profileLookup = { uuid -> replay.shadow.players.profile(uuid)?.toPacketEntry() }
            mirror.targetFace = { replay.shadow.localPlayer.target?.face ?: -1 }
            val projection =
                RecorderProjection(replay.shadow, mirror, ProjectionOptions(predictBlocks = !replay.recordedTicks))
            projection.recorderMissing = { id ->
                val world = minecraft.world
                world != null && world.getEntity(id)?.removed != false
            }
            replay.addConsumer(projection)
            replay.addConsumer(resetWatcher)
            replay.tickHook = onWorldTick
            replay.listeners.add(object : ReplayListener {
                override fun onPlayingChanged(playing: Boolean) = applyTimerScale()

                override fun onSpeedChanged(speed: Double) = applyTimerScale()

                override fun onPositionChanged(positionNanos: Long, mode: DeliveryMode) {
                    if (mode == DeliveryMode.SEEK) {
                        minecraft.world?.chunkSource?.tick()
                        syncChunkFrames = SYNC_CHUNK_FRAMES_AFTER_SEEK
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
            advanceToFirstPosition(replay)
            camera.attach(replay)
            lastFrameNanos = Long.MIN_VALUE
            lastPositionNanos = Long.MIN_VALUE
            onOpened(target)
            applyTimerScale()
            logger.info("Afterimage opened replay {} ({} s)", target.fileName, replay.durationNanos / Nanos.PER_SECOND)
            true
        } catch (error: Throwable) {
            lastError = error.message ?: error.javaClass.simpleName
            logger.error("Afterimage could not open replay {}", target, error)
            close()
            false
        }
    }

    private fun cameraProfile(replay: ReplaySession): GameProfile {
        val identity = replay.shadow.recorderIdentity
        val uuid = identity.uuid
        val name = identity.name
        if (uuid != null && !name.isNullOrEmpty()) return GameProfile(uuid, name)
        return minecraft.session?.profile ?: VirtualConnection.ANONYMOUS
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
        camera.detach()
        runCatching { replay.close() }.onFailure { logger.error("Afterimage failed to close replay", it) }
        val connection = mirror
        mirror = null
        appliedPack = null
        packReopenFrames = 0
        (minecraft as MinecraftAccessor).`afterimage$timer`().tpsScale = 1f
        if (leaveWorld) {
            if (minecraft.world != null) minecraft.setWorld(null)
            minecraft.openScreen(TitleScreen())
        } else {
            runCatching { connection?.close() }.onFailure {
                logger.warn(
                    "Afterimage could not close the replay connection",
                    it
                )
            }
        }
        onClosed()
    }

    private fun blockingScreen(): Boolean {
        val screen = minecraft.screen ?: return false
        return screen !is WorkspaceScreen && screen !is ExportScreen
    }

    fun ownsWorld(): Boolean {
        val current = minecraft.player?.networkHandler ?: return true
        val connection = (current as? ReplayConnectionOwner)?.`afterimage$connection`() ?: return true
        return AfterimageHooks.isReplayConnection(connection)
    }

    fun advancePlayback() {
        val replay = session ?: return
        val now = System.nanoTime()
        val delta =
            if (lastFrameNanos == Long.MIN_VALUE) 0L else (now - lastFrameNanos).coerceIn(0L, Nanos.ofMillis(250))
        lastFrameNanos = now
        if (delta > 0L) replay.advance(delta)
    }

    fun onFrame(tickDelta: Float, workspaceOpen: Boolean, viewportHovered: Boolean, keyboardFree: Boolean = true) {
        val replay = session ?: return
        if (minecraft.world != null && minecraft.player != null && !ownsWorld()) {
            abandon()
            return
        }
        if (minecraft.world == null && mirror?.isOpen == true && replay.packetsDelivered > 0 && minecraft.screen is TitleScreen) {
            close()
            return
        }
        if (packReopenFrames > 0) {
            packReopenFrames--
            if (minecraft.screen is ProgressScreen) {
                packReopenFrames = 0
                awaitingWorld = true
                reopenWorkspace = true
            }
        }
        if (awaitingWorld && minecraft.world != null && minecraft.player != null && !blockingScreen()) {
            awaitingWorld = false
            onWorldReady(reopenWorkspace)
        }
        val now = System.nanoTime()
        applyRecordedViewDistance(replay, now)
        val position = replay.positionNanos
        val cameraDelta = if (lastPositionNanos == Long.MIN_VALUE) 0L else (position - lastPositionNanos).coerceIn(
            0L,
            Nanos.ofMillis(250)
        )
        lastPositionNanos = position
        val wallDelta = if (lastCameraFrameNanos == Long.MIN_VALUE) 0L else (now - lastCameraFrameNanos).coerceIn(
            0L,
            Nanos.ofMillis(250)
        )
        lastCameraFrameNanos = now
        camera.onFrame(cameraDelta, wallDelta, tickDelta, workspaceOpen, viewportHovered, keyboardFree)
    }

    private var packetFailures = 0L

    fun onPacketFailure(error: Throwable) {
        packetFailures++
        if (packetFailures <= 20 || packetFailures % 500 == 0L) logger.warn(
            "Afterimage replay packet {} failed ({}): {}",
            packetFailures,
            mirror?.lastDelivered?.let { "id 0x${Integer.toHexString(it.packetId)} ${it.direction} at ${it.timestampNanos / 1_000_000} ms" }
                ?: "unknown packet",
            error.toString())
    }

    fun onClientTick() {
        mirror?.tick()
    }

    private fun advanceToFirstPosition(replay: ReplaySession) {
        val limit = minOf(replay.endNanos, replay.positionNanos + FIRST_POSITION_SEARCH_NANOS)
        while (!replay.shadow.localPlayer.hasPosition && replay.positionNanos < limit) {
            replay.seek(minOf(limit, replay.positionNanos + FIRST_POSITION_STEP_NANOS), linear = true)
        }
    }

    private fun applyRecordedViewDistance(replay: ReplaySession, now: Long) {
        if (!matchRecordedViewDistance) return
        if (now - lastViewDistanceCheckNanos < Nanos.ofSeconds(1)) return
        lastViewDistanceCheckNanos = now
        if (userViewDistance < 0) userViewDistance = minecraft.options.viewDistance
        val player = replay.shadow.localPlayer
        if (!player.hasPosition) return
        val centerX = player.x.toInt() shr 4
        val centerZ = player.z.toInt() shr 4
        var radius = 0
        replay.shadow.level.chunks.forEach { _, chunk ->
            val distance = maxOf(abs(chunk.chunkX - centerX), abs(chunk.chunkZ - centerZ))
            if (distance in (radius + 1)..MAX_VIEW_DISTANCE) radius = distance
        }
        val desired = maxOf(userViewDistance, minOf(radius, MAX_VIEW_DISTANCE))
        if (minecraft.options.viewDistance != desired) minecraft.options.viewDistance = desired
    }

    private fun restoreViewDistance() {
        if (userViewDistance >= 0 && minecraft.options.viewDistance != userViewDistance) minecraft.options.viewDistance =
            userViewDistance
        userViewDistance = -1
        lastViewDistanceCheckNanos = 0L
    }

    private fun applyTimerScale() {
        val replay = session ?: return
        (minecraft as MinecraftAccessor).`afterimage$timer`().tpsScale = if (replay.playing) replay.speed.toFloat() else 1f
    }

    private companion object {
        const val MAX_VIEW_DISTANCE = 16
        const val SYNC_CHUNK_FRAMES_AFTER_SEEK = 2
        val FIRST_POSITION_SEARCH_NANOS = Nanos.ofSeconds(30)
        val FIRST_POSITION_STEP_NANOS = Nanos.ofMillis(100)
    }
}
