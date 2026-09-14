package gg.sona.recast.mc

import gg.sona.recast.camera.CameraMode
import gg.sona.recast.mc.ui.WorkspaceScreen
import io.netty.channel.Channel
import net.minecraft.block.Block
import net.minecraft.block.state.BlockState
import net.minecraft.client.Minecraft
import net.minecraft.client.TickTimer
import net.minecraft.client.entity.living.player.ClientPlayerEntity
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity
import net.minecraft.client.gui.GameGui
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen
import net.minecraft.client.render.model.entity.HumanoidModel
import net.minecraft.client.render.pipeline.RenderTarget
import net.minecraft.client.render.world.ChunkRenderDispatcher
import net.minecraft.client.render.world.RenderChunk
import net.minecraft.client.sound.instance.SoundInstance
import net.minecraft.client.sound.system.SoundEngine
import net.minecraft.entity.Entity
import net.minecraft.entity.living.LivingEntity
import net.minecraft.entity.living.player.PlayerEntity
import net.minecraft.network.Connection
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import net.minecraft.world.HitResult
import net.minecraft.world.World
import net.minecraft.world.chunk.WorldChunk
import org.apache.logging.log4j.LogManager
import java.util.*
import kotlin.math.floor
import kotlin.math.roundToInt

object RecastHooks {
    const val REPLAYS_BUTTON_ID = 0x5243

    private val logger = LogManager.getLogger("Recast")
    private val buildingReplayConnection = ThreadLocal.withInitial { false }

    @Volatile
    private var runtime: RecastRuntime? = null

    fun bind(runtime: RecastRuntime) {
        this.runtime = runtime
    }

    fun unbind() {
        runtime = null
    }

    fun <T> asReplayConnection(block: () -> T): T {
        buildingReplayConnection.set(true)
        try {
            return block()
        } finally {
            buildingReplayConnection.set(false)
        }
    }

    private val replayConnections: MutableSet<Connection> = Collections.newSetFromMap(WeakHashMap())

    @JvmStatic
    fun registerReplayConnection(connection: Connection) {
        replayConnections.add(connection)
    }

    @JvmStatic
    fun isReplayConnection(connection: Connection?): Boolean =
        connection != null && replayConnections.contains(connection)

    @JvmStatic
    fun onReplayConnectionError(connection: Connection, error: Throwable): Boolean {
        if (!isReplayConnection(connection)) return false
        runtime?.replayer?.onPacketFailure(error) ?: logger.warn("Recast replay packet failed", error)
        return true
    }

    @JvmStatic
    fun onReplayDisconnectSuppressed(reason: Text?) {
        logger.debug("Recast ignored a disconnect on the replay connection: {}", reason?.string)
    }

    @JvmStatic
    fun onPlayConnection(connection: Connection) {
        if (buildingReplayConnection.get()) return
        guarded("play connection") {
            if (it.replayer.isReplaying) it.replayer.abandon()
            it.recorder.onPlayConnection(connection)
        }
    }

    @JvmStatic
    fun onPlayDisconnect(connection: Connection) =
        guarded("play disconnect") { it.recorder.onConnectionClosed(connection) }

    @JvmStatic
    fun onCompressionChanged(channel: Channel) =
        guarded("compression change") { it.recorder.onCompressionChanged(channel) }

    @JvmStatic
    fun onFrameStart(tickDelta: Float) = guarded("frame start") { it.onFrameStart(tickDelta) }

    @JvmStatic
    fun onFrameEnd() = guarded("frame end") { it.onFrameEnd() }

    @JvmStatic
    fun cameraFov(): Float = runtime?.cameraDriver?.fovOverride() ?: 0f

    @JvmStatic
    fun exportOrthoScale(): Float? = runtime?.cameraDriver?.orthoScaleOverride()

    @JvmStatic
    fun observeFov(value: Float) {
        runtime?.recorder?.observeFov(value)
    }

    @JvmStatic
    fun onCameraSetup(tickDelta: Float) = guarded("camera setup") {
        if (it.preview.rendering) {
            it.cameraDriver.onCameraSetup()
            return@guarded
        }
        it.recorder.onCameraSetup(tickDelta)
        it.cameraDriver.onCameraSetup()
        it.viewportPicker.onCameraSetup(tickDelta)
        it.workspace.applyWorldViewport()
    }

    @JvmStatic
    fun onHandSetup() = guarded("hand setup") {
        it.recorder.onHandSetup()
        it.cameraDriver.onHandSetup()
    }

    @JvmStatic
    fun onHudRender(): Boolean {
        val current = runtime ?: return false
        current.thumbnails.onWorldRendered()
        if (current.exporter.isExporting) return current.minecraft.options.hideGui
        if (!current.workspace.isOpen) return false
        if (current.minecraft.options.hideGui) return true
        current.workspace.applyWorldViewport()
        return false
    }

    @JvmStatic
    fun onHudRendered(tickDelta: Float) = guarded("hud overlays") {
        it.screenMirror.render(tickDelta)
        it.recordingHud.render(it.recorder, it.settings.recordingIndicator, it.workspace.isOpen)
    }

    private var localEditDepth = 0

    @JvmStatic
    fun localEditBegin() {
        localEditDepth++
    }

    @JvmStatic
    fun localEditEnd() {
        if (localEditDepth > 0) localEditDepth--
    }

    @JvmStatic
    fun onLocalBlockChange(world: World, pos: BlockPos, state: BlockState) {
        if (localEditDepth <= 0) return
        val current = runtime ?: return
        if (world !== current.minecraft.world) return
        val stateId = Block.STATE_REGISTRY.getId(state)
        if (stateId < 0) return
        current.recorder.onLocalBlockChange(pos.toLong(), stateId)
    }

    @JvmStatic
    fun onReplayWorldSwitch() = guarded("replay world switch") { it.replayer.onWorldSwitch() }

    @JvmStatic
    fun keepsReplayScreen(requested: Screen?): Boolean {
        if (requested != null) return true
        val current = runtime?.minecraft?.screen ?: return false
        return current is WorkspaceScreen || current is ExportScreen
    }

    @JvmStatic
    fun beforeSlotClick(screen: InventoryMenuScreen) = guarded("slot click") { it.recorder.beforeSlotClick(screen) }

    @JvmStatic
    fun afterSlotClick(screen: InventoryMenuScreen) = guarded("slot click") { it.recorder.afterSlotClick(screen) }

    @JvmStatic
    fun afterPick() = guarded("crosshair target") { it.cameraDriver.overrideCrosshairTarget() }

    @JvmStatic
    fun chatFocusMirrored(): Boolean = runtime?.screenMirror?.chatFocused() == true

    @JvmStatic
    fun onClientTickStart() {
        localEditDepth = 0
        guarded("client tick start") { it.recorder.onClientTickStart() }
    }

    @JvmStatic
    fun onLivingTick(entity: LivingEntity) {
        val current = runtime ?: return
        if (!current.replayer.isReplaying) return
        val player = entity as? LocalClientPlayerEntity ?: return
        player.noClip = true
        player.abilities.flying = true
        player.abilities.canFly = true
        player.onGround = false
        player.velocityX = 0.0
        player.velocityY = 0.0
        player.velocityZ = 0.0
        player.fallDistance = 0f
    }

    @JvmStatic
    fun cameraRoll(): Float = runtime?.cameraDriver?.rollDegrees() ?: 0f

    @JvmStatic
    fun onWorldRendered(tickDelta: Float) =
        guarded("world overlays") { if (!it.preview.rendering) it.gizmoRenderer.render(tickDelta) }

    @JvmStatic
    fun restorePose(model: HumanoidModel) {
        runtime?.poseDriver?.restore(model)
    }

    @JvmStatic
    fun applyPose(model: HumanoidModel, entity: Entity) = guarded("pose") { it.poseDriver.apply(model, entity) }

    @JvmStatic
    fun onWorldPassComplete(tickDelta: Float, finishNanos: Long) =
        guarded("camera preview") { it.preview.onWorldRendered(tickDelta, finishNanos) }

    @JvmStatic
    fun hideHand(): Boolean = runtime?.cameraDriver?.hideHand() == true

    @JvmStatic
    fun secondaryRender(): Boolean = runtime?.preview?.rendering == true

    @JvmStatic
    fun rebuildChunk(dispatcher: ChunkRenderDispatcher, chunk: RenderChunk): Boolean {
        val current = runtime
        if (current != null && ((current.exporter.isExporting && current.exporter.synchronousChunks) || current.replayer.syncChunkFrames > 0)) return dispatcher.rebuildSync(
            chunk
        )
        return dispatcher.rebuildAsync(chunk)
    }

    @JvmStatic
    fun renderTargetOverride(): RenderTarget? = runtime?.exporter?.activeTarget()

    @JvmStatic
    fun cloudTicks(tickDelta: Float): Int {
        val current = runtime ?: return Int.MIN_VALUE
        if (!current.replayer.isReplaying) return Int.MIN_VALUE
        val replay = current.replayer.session ?: return Int.MIN_VALUE
        val ticks = (replay.positionNanos - replay.startNanos).toDouble() / 50_000_000.0 - tickDelta
        return ticks.roundToInt()
    }

    @JvmStatic
    fun animationTime(): Long {
        val current = runtime ?: return Minecraft.getTime()
        val replay = current.replayer.session ?: return Minecraft.getTime()
        return replay.positionNanos / 1_000_000L
    }

    @JvmStatic
    fun forceBlockOutline(): Boolean {
        val current = runtime ?: return false
        if (!current.replayer.isReplaying) return false
        val settings = current.cameraDriver.settings
        if (!settings.showBlockOutline || settings.mode != CameraMode.FIRST_PERSON || !settings.targetsRecorder()) return false
        if (current.minecraft.camera !is PlayerEntity) return false
        val target = current.minecraft.crosshairTarget ?: return false
        return target.type == HitResult.Type.BLOCK
    }

    @JvmStatic
    fun handTarget(): ClientPlayerEntity? = runtime?.cameraDriver?.handTarget()

    @JvmStatic
    fun onEntityCreated(entity: Entity) {
        runtime?.onEntityCreated(entity)
    }

    @JvmStatic
    fun borderTime(): Long {
        val current = runtime ?: return System.currentTimeMillis()
        val replay = current.replayer.session ?: return System.currentTimeMillis()
        return replay.positionNanos / 1_000_000L
    }

    @JvmStatic
    fun reseedForPacket(timestampNanos: Long) {
        runtime?.reseedForPacket(timestampNanos)
    }

    @JvmStatic
    fun vignetteBegin(gui: GameGui) {
        runtime?.vignetteBegin(gui)
    }

    @JvmStatic
    fun vignetteEnd(gui: GameGui) {
        runtime?.vignetteEnd(gui)
    }

    @JvmStatic
    fun displayTickRandom(): Random = runtime?.displayTickRandom() ?: Random()

    @JvmStatic
    fun onWorldPassEnd() = guarded("depth capture") {
        it.exporter.onWorldPassEnd()
        if (!it.exporter.isExporting && !it.preview.rendering) it.lookPreview.onWorldPassEnd()
    }

    @JvmStatic
    fun chunkDeadline(finishNanos: Long): Long = runtime?.exporter?.chunkDeadline(finishNanos) ?: finishNanos

    const val HUD_HOTBAR = 0
    const val HUD_STATUS = 1
    const val HUD_EXPERIENCE = 2
    const val HUD_BOSS = 3
    const val HUD_SCOREBOARD = 4
    const val HUD_VIGNETTE = 5
    const val HUD_CHAT = 6

    @JvmStatic
    fun hideHudPart(part: Int): Boolean {
        val visuals = runtime?.visualsController ?: return false
        return when (part) {
            HUD_HOTBAR -> visuals.hideHotbar()
            HUD_STATUS -> visuals.hideStatusBars()
            HUD_EXPERIENCE -> visuals.hideExperience()
            HUD_BOSS -> visuals.hideBossBar()
            HUD_SCOREBOARD -> visuals.hideScoreboard()
            HUD_VIGNETTE -> visuals.hideVignette()
            HUD_CHAT -> visuals.hideChat()
            else -> false
        }
    }

    @JvmStatic
    fun hideNametags(): Boolean = runtime?.visualsController?.hideNametags() == true

    @JvmStatic
    fun hideNametagFor(entity: Entity): Boolean = runtime?.visualsController?.hideNametagFor(entity) == true

    @JvmStatic
    fun hideParticles(): Boolean = runtime?.visualsController?.hideParticles() == true

    @JvmStatic
    fun hideParticleType(type: Int): Boolean = runtime?.visualsController?.hideParticleType(type) == true

    @JvmStatic
    fun onBlockMiningProgress(breakerId: Int, pos: BlockPos, progress: Int) {
        runtime?.recorder?.onBlockMiningProgress(breakerId, pos.toLong(), progress)
    }

    @JvmStatic
    fun onSoundPlay(engine: SoundEngine, sound: SoundInstance): Boolean {
        val current = runtime ?: return false
        return current.exporter.sounds.onPlay(engine, sound)
    }

    @JvmStatic
    fun hideSky(): Boolean = runtime?.visualsController?.hideSky() == true

    @JvmStatic
    fun hideClouds(): Boolean = runtime?.visualsController?.hideClouds() == true

    @JvmStatic
    fun hideWeather(): Boolean = runtime?.visualsController?.hideWeather() == true

    @JvmStatic
    fun onFogSetup() {
        runtime?.visualsController?.onFogSetup()
    }

    @JvmStatic
    fun onClearColor() {
        runtime?.visualsController?.onClearColor()
    }

    @JvmStatic
    fun beforeEntityRender(entity: Entity) {
        val current = runtime ?: return
        if (!current.replayer.isReplaying) return
        current.cameraDriver.entitySmoother.applyRotation(current.replayer.session, entity)
    }

    @JvmStatic
    fun suppressHurtCamera(): Boolean {
        val current = runtime ?: return false
        return current.replayer.isReplaying && !current.cameraDriver.hurtCameraAllowed()
    }

    @JvmStatic
    fun smoothedPosition(entity: Entity): DoubleArray? {
        val current = runtime ?: return null
        if (!current.replayer.isReplaying) return null
        return current.cameraDriver.entitySmoother.renderPosition(current.replayer.session, entity)
    }

    @JvmStatic
    fun onTimerAdvanced(timer: TickTimer) = guarded("timer advance") { it.onTimerAdvanced(timer) }


    @JvmStatic
    fun worldFrozen(): Boolean {
        val current = runtime ?: return false
        return !current.clock.worldTickAllowed
    }

    @JvmStatic
    fun shouldHideEntity(entity: Entity): Boolean {
        val current = runtime ?: return false
        if (current.screenMirror.renderingModel) return false
        if (current.replayer.isReplaying && entity === current.minecraft.player) return true
        return current.cameraDriver.shouldHide(entity) || current.visualsController.shouldHide(entity)
    }

    @JvmStatic
    fun onChunkLoaded(previous: WorldChunk?, empty: WorldChunk?, next: WorldChunk) {
        val world = runtime?.minecraft?.world ?: return
        var migrated = 0
        if (previous != null) {
            for (section in previous.entities) {
                for (entity in ArrayList(section)) {
                    if (floor(entity.x / 16.0).toInt() == next.chunkX && floor(entity.z / 16.0)
                            .toInt() == next.chunkZ
                    ) {
                        next.addEntity(entity)
                        migrated++
                    } else {
                        entity.inChunk = false
                    }
                }
            }
            for (blockEntity in ArrayList(previous.blockEntities.values)) world.unloadBlockEntity(blockEntity)
        }
        if (empty != null) {
            for (section in empty.entities) {
                for (entity in ArrayList(section)) {
                    if (entity.removed) {
                        section.remove(entity)
                        continue
                    }
                    if (floor(entity.x / 16.0).toInt() == next.chunkX && floor(entity.z / 16.0)
                            .toInt() == next.chunkZ
                    ) {
                        section.remove(entity)
                        next.addEntity(entity)
                        migrated++
                    }
                }
            }
        }
        if (migrated > 0) logger.debug(
            "Recast migrated {} entities into loaded chunk {},{}",
            migrated,
            next.chunkX,
            next.chunkZ
        )
    }

    @JvmStatic
    fun openWorkspace() = guarded("open workspace") { it.workspace.open() }

    private inline fun guarded(what: String, action: (RecastRuntime) -> Unit) {
        val current = runtime ?: return
        try {
            action(current)
        } catch (error: Throwable) {
            logger.error("Recast $what failed", error)
        }
    }
}
