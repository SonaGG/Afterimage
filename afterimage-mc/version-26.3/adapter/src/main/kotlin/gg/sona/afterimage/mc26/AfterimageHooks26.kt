package gg.sona.afterimage.mc26

import gg.sona.afterimage.mc26.mixin.GameRendererAccessor
import gg.sona.afterimage.mc26.mixin.TimerAccessor
import gg.sona.afterimage.mc26.protocol.Protocol26Support
import gg.sona.afterimage.mc26.replay.RecorderProjection26
import gg.sona.afterimage.mc26.replay.ReplayConnections26
import gg.sona.afterimage.mc26.ui.ExportScreen26
import gg.sona.afterimage.mc26.ui.WorkspaceScreen26
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.SectionOcclusionGraph
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import net.minecraft.client.renderer.extract.LevelExtractor
import net.minecraft.client.renderer.fog.FogData
import net.minecraft.client.renderer.state.GameRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.core.BlockPos
import net.minecraft.network.Connection
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector4f
import org.slf4j.LoggerFactory

object AfterimageHooks26 {
    private val logger = LoggerFactory.getLogger("Afterimage")

    @Volatile
    private var runtime: AfterimageRuntime26? = null

    fun install() {
        Protocol26Support.install()
    }

    private fun runtime(): AfterimageRuntime26? {
        runtime?.let { return it }
        val minecraft = Minecraft.getInstance() ?: return null
        if (!minecraft.isSameThread) return null
        return try {
            AfterimageRuntime26(minecraft).also { runtime = it }
        } catch (error: Throwable) {
            logger.error("Afterimage could not start", error)
            null
        }
    }

    val current: AfterimageRuntime26? get() = runtime

    @JvmStatic
    fun onConnection(connection: Connection) = runTask("connection") {
        if (!ReplayConnections26.isReplay(connection)) it.recorder.onConnection(connection)
    }

    @JvmStatic
    fun onPlayConnection(connection: Connection) = runTask("play connection") {
        if (ReplayConnections26.isReplay(connection)) return@runTask
        if (it.replayer.isReplaying) it.replayer.abandon()
        it.recorder.onConnection(connection)
    }

    @JvmStatic
    fun onDisconnect() = runTask("disconnect") { it.recorder.onDisconnect() }

    @JvmStatic
    fun onClientTickStart() = runTask("client tick start") { it.onClientTickStart() }

    @JvmStatic
    fun onClientTick() = runTask("client tick") { it.onClientTick() }

    @JvmStatic
    fun onFrameStart(tickDelta: Float) = runTask("frame start") { it.onFrameStart(tickDelta) }

    @JvmStatic
    fun onCameraExtracted(state: CameraRenderState, fov: Float) = runTask("camera extract") {
        it.cameraDriver.orthoScaleOverride()?.let { scale ->
            val window = it.minecraft.window
            val aspect = window.width.toFloat() / window.height.coerceAtLeast(1)
            val halfHeight = scale
            val halfWidth = halfHeight * aspect
            val zeroToOne = com.mojang.blaze3d.systems.RenderSystem.getDevice().deviceInfo.isZZeroToOne
            state.projectionMatrix.setOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, state.depthFar, -state.depthFar, zeroToOne)
        }
        if (it.replayer.isReplaying && state.smartCull) {
            val level = it.minecraft.level
            if (level != null && level.getBlockState(net.minecraft.core.BlockPos.containing(state.pos)).isSolidRender) state.smartCull = false
        }
        it.recorder.camera.onCameraExtracted(state, fov)
        it.viewportPicker.onCameraExtracted(state)
        it.viewportFit.update()
        it.viewportFit.apply(state.projectionMatrix)
    }

    @JvmStatic
    fun hudProjection(projection: Matrix4f): Matrix4f = runtime?.viewportFit?.apply(projection) ?: projection

    @JvmStatic
    fun guiScissor(x: Int, y: Int, width: Int, height: Int): IntArray = runtime?.viewportFit?.scissor(x, y, width, height) ?: intArrayOf(x, y, width, height)

    @JvmStatic
    fun onCameraUpdated(camera: Camera) = runTask("camera update") { it.cameraDriver.onCameraUpdated(camera) }

    @JvmStatic
    fun cameraFov(): Float = runtime?.cameraDriver?.fovOverride() ?: 0f

    @JvmStatic
    fun orthoScale(): Float? = runtime?.cameraDriver?.orthoScaleOverride()

    @JvmStatic
    fun onBob(bob: Matrix4fc) = runTask("camera bob") { it.recorder.camera.onBob(bob) }

    @JvmStatic
    fun handPose(viewRotation: Matrix4fc, pose: Matrix4fc, state: CameraRenderState): Matrix4fc {
        val current = runtime ?: return pose
        runCatching { current.recorder.camera.onHand(pose, state) }.onFailure { logger.error("Afterimage hand capture failed", it) }
        if (!current.replayer.isReplaying) return pose
        return current.cameraDriver.handPose(viewRotation, pose)
    }

    @JvmStatic
    fun hideHand(): Boolean = runtime?.let { it.replayer.isReplaying && it.cameraDriver.hideHand() } == true

    @JvmStatic
    fun onExtracted(state: GameRenderState) = runTask("extract") { current ->
        val visuals = current.visualsController
        if (visuals.hideClouds()) state.levelRenderState.cloudColor = 0
        if (visuals.hideWeather()) state.levelRenderState.weatherRenderState.reset()
        if (state.shouldRenderLevel) current.preview.onMainExtracted(state.levelRenderState.chunkLoadingRenderState)
    }

    @JvmStatic
    fun renderSky(original: Boolean): Boolean = if (runtime?.visualsController?.hideSky() == true) false else original

    @JvmStatic
    fun fogColor(original: Vector4f): Vector4f = runtime?.visualsController?.clearColor(original) ?: original

    @JvmStatic
    fun onFogSetup(fog: FogData) {
        runtime?.visualsController?.onFogSetup(fog)
    }

    @JvmStatic
    fun onWorldRendered() = runTask("world rendered") { it.onWorldRendered() }

    @JvmStatic
    fun onLevelRendered() = runTask("level rendered") { it.onLevelRendered() }

    @JvmStatic
    fun onFrameEnd() = runTask("frame end") { it.onFrameEnd() }

    @JvmStatic
    fun onTimerAdvanced(timer: TimerAccessor) = runTask("timer advance") { it.onTimerAdvanced(timer) }

    @JvmStatic
    fun worldFrozen(): Boolean {
        val current = runtime ?: return false
        return !current.clock.worldTickAllowed
    }

    @JvmStatic
    fun onFrozenRendererTick(renderer: GameRenderer) = runTask("frozen renderer tick") {
        if (it.replayer.session?.playing == true || it.minecraft.level == null) return@runTask
        (renderer as GameRendererAccessor).afterimage_lightmapExtractor().tick()
        renderer.mainCamera().tick()
    }

    @JvmStatic
    fun sizeOverride(): IntArray? = runtime?.exportPlatform?.sizeOverride

    @JvmStatic
    fun onHudExtracted(graphics: GuiGraphicsExtractor, partialTick: Float) = runTask("hud overlays") {
        it.screenMirror.extract(graphics, partialTick)
        it.recordingHud.render(graphics, it.recorder, it.settings.recordingIndicator, it.workspace.isOpen)
    }

    @JvmStatic
    fun inventoryModel(fallback: LivingEntity): LivingEntity = runtime?.screenMirror?.modelEntity(fallback) ?: fallback

    @JvmStatic
    fun suppressMenuBlur(): Boolean = runtime?.screenMirror?.extracting == true

    @JvmStatic
    fun chatFocusMirrored(): Boolean = runtime?.screenMirror?.chatFocused() == true

    @JvmStatic
    fun beforeSlotClick(screen: AbstractContainerScreen<*>) = runTask("slot click") { it.recorder.beforeSlotClick(screen) }

    @JvmStatic
    fun afterSlotClick(screen: AbstractContainerScreen<*>) = runTask("slot click") { it.recorder.afterSlotClick(screen) }

    @JvmStatic
    fun updateInventoryInPlace(menuSlot: Boolean, slot: Int, item: net.minecraft.world.item.ItemStack): Boolean {
        val current = runtime ?: return false
        if (!current.replayer.isReplaying) return false
        val player = current.minecraft.player ?: return false
        val existing = if (menuSlot) {
            player.inventoryMenu.slots.getOrNull(slot)?.item
        } else {
            if (slot in 0 until player.inventory.containerSize) player.inventory.getItem(slot) else null
        } ?: return false
        if (existing.isEmpty || item.isEmpty) return false
        if (!net.minecraft.world.item.ItemStack.isSameItemSameComponents(existing, item)) return false
        if (existing.count != item.count) existing.count = item.count
        return true
    }

    @JvmStatic
    fun forceBlockOutline(): Boolean {
        val current = runtime ?: return false
        if (!current.replayer.isReplaying || !current.cameraDriver.overridesCrosshair()) return false
        if (current.minecraft.gui.hud.isHidden) return false
        return current.minecraft.hitResult?.type == net.minecraft.world.phys.HitResult.Type.BLOCK
    }

    @JvmStatic
    fun afterPick() = runTask("crosshair target") { it.cameraDriver.overrideCrosshairTarget() }

    @JvmStatic
    fun onLocalPlayerTick(player: LocalPlayer) {
        val current = runtime ?: return
        if (!current.replayer.isReplaying) return
        if (player.input is net.minecraft.client.player.KeyboardInput) player.input = net.minecraft.client.player.ClientInput()
        player.noPhysics = true
        player.isNoGravity = true
        player.abilities.flying = true
        player.abilities.mayfly = true
        player.abilities.invulnerable = true
        player.setOnGround(false)
        player.deltaMovement = Vec3.ZERO
        player.fallDistance = 0.0
    }

    @JvmStatic
    fun fovModifier(player: AbstractClientPlayer, original: Float): Float {
        val current = runtime ?: return original
        return if (current.replayer.isReplaying && player === current.minecraft.player) 1f else original
    }

    @JvmStatic
    fun cameraNoPhysics(player: Player): Boolean {
        val current = runtime ?: return false
        return current.replayer.isReplaying && player === current.minecraft.player
    }

    @JvmStatic
    fun suppressForcedFrames(): Boolean {
        val current = runtime ?: return false
        return current.replayer.isReplaying || current.workspace.rendering
    }

    @JvmStatic
    fun keepsReplayScreen(requested: Screen?): Boolean {
        val current = runtime ?: return false
        if (!current.replayer.isReplaying) return false
        if (gg.sona.afterimage.mc26.ui.ScreenGuard26.closing) return false
        val blocked = requested !is WorkspaceScreen26 && requested !is ExportScreen26
        if (blocked) logger.debug("Afterimage kept the replay screen, ignoring {}", requested?.javaClass?.simpleName ?: "null")
        return blocked
    }

    @JvmStatic
    fun shouldHideEntity(entity: Entity): Boolean {
        val current = runtime ?: return false
        if (current.screenMirror.renderingModel) return false
        if (current.replayer.isReplaying && (entity === current.minecraft.player || RecorderProjection26.isCamera(entity))) return true
        return current.cameraDriver.shouldHide(entity) || current.visualsController.shouldHide(entity)
    }

    @JvmStatic
    fun hideNametags(entity: Entity): Boolean {
        val visuals = runtime?.visualsController ?: return false
        return visuals.hideNametags() || visuals.hideNametagFor(entity)
    }

    @JvmStatic
    fun beforeOptionsSave() {
        runtime?.replayer?.beforeOptionsSave()
    }

    @JvmStatic
    fun afterOptionsSave() {
        runtime?.replayer?.afterOptionsSave()
    }

    @JvmStatic
    fun isPreviewExtractor(extractor: LevelExtractor): Boolean = runtime?.preview?.owns(extractor) == true

    @JvmStatic
    fun previewRendering(): Boolean = runtime?.preview?.rendering == true

    @JvmStatic
    fun onPropagationScheduled(graph: SectionOcclusionGraph, section: SectionRenderDispatcher.RenderSection) {
        runtime?.preview?.onPropagationScheduled(graph, section)
    }

    private val visualRandoms = java.util.Collections.synchronizedList(ArrayList<java.lang.ref.WeakReference<net.minecraft.util.RandomSource>>())

    @JvmStatic
    fun registerVisualRandom(random: net.minecraft.util.RandomSource) {
        visualRandoms.add(java.lang.ref.WeakReference(random))
    }

    fun reseedVisualRandoms(seed: Long) {
        synchronized(visualRandoms) {
            val iterator = visualRandoms.iterator()
            var index = 0L
            while (iterator.hasNext()) {
                val random = iterator.next().get()
                if (random == null) iterator.remove() else random.setSeed(seed xor (0x100L + index++))
            }
        }
    }

    @JvmStatic
    fun particleOrigin(camera: Camera): Vec3 {
        val current = runtime ?: return camera.position()
        if (!current.replayer.isReplaying) return camera.position()
        return current.animateOrigin()?.position() ?: camera.position()
    }

    @JvmStatic
    fun animateRandom(): net.minecraft.util.RandomSource {
        val current = runtime ?: return net.minecraft.util.RandomSource.createThreadLocalInstance()
        if (!current.replayer.isReplaying) return net.minecraft.util.RandomSource.createThreadLocalInstance()
        return net.minecraft.util.RandomSource.createThreadLocalInstance(current.animateSeed)
    }

    @JvmStatic
    fun frameRandom(): net.minecraft.util.RandomSource {
        val session = runtime?.replayer?.session ?: return net.minecraft.util.RandomSource.createThreadLocalInstance()
        return net.minecraft.util.RandomSource.createThreadLocalInstance(session.positionNanos)
    }

    @JvmStatic
    fun visualMillis(): Long {
        val session = runtime?.replayer?.session ?: return net.minecraft.util.Util.getMillis()
        return session.positionNanos / 1_000_000L
    }

    @JvmStatic
    fun onEntityCreated(entity: Entity) {
        runtime?.onEntityCreated(entity)
    }

    @JvmStatic
    fun onEntitySpawned(id: Int) {
        runtime?.onEntitySpawned(id)
    }

    @JvmStatic
    fun onParticleCreated(particle: net.minecraft.client.particle.Particle) {
        runtime?.onParticleCreated(particle)
    }

    @JvmStatic
    fun hideParticles(): Boolean = runtime?.visualsController?.hideParticles() == true

    @JvmStatic
    fun hideParticleType(type: Int): Boolean = runtime?.visualsController?.hideParticleType(type) == true

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
    fun beforeEntityExtract(entity: Entity) {
        val current = runtime ?: return
        if (!current.replayer.isReplaying) return
        current.cameraDriver.entitySmoother.applyRotation(current.replayer.session, entity)
    }

    @JvmStatic
    fun smoothedPosition(entity: Entity): Vec3? {
        val current = runtime ?: return null
        if (!current.replayer.isReplaying) return null
        val smoothed = current.cameraDriver.entitySmoother.renderPosition(current.replayer.session, entity) ?: return null
        return Vec3(smoothed[0], smoothed[1], smoothed[2])
    }

    @JvmStatic
    fun suppressHurtCamera(): Boolean {
        val current = runtime ?: return false
        return current.replayer.isReplaying && !current.cameraDriver.hurtCameraAllowed()
    }

    @JvmStatic
    fun onSoundPlay(engine: SoundEngine, sound: SoundInstance): Boolean {
        val current = runtime ?: return false
        return (current.exporter.sounds as gg.sona.afterimage.mc26.replay.SoundCapture26).onPlay(engine, sound)
    }

    @JvmStatic
    fun onLocalBlockChange(pos: BlockPos, state: BlockState) = runTask("local block change") { it.recorder.onLocalBlockChange(pos, state) }

    @JvmStatic
    fun onBlockMiningProgress(breakerId: Int, pos: BlockPos, progress: Int) = runTask("mining progress") { it.recorder.onBlockMiningProgress(breakerId, pos, progress) }

    @JvmStatic
    fun reseedForPacket(timestampNanos: Long) {
        runtime?.reseedForPacket(timestampNanos)
    }

    @JvmStatic
    fun openWorkspace() = runTask("open workspace") { it.workspace.open() }

    @JvmStatic
    fun onReplayConnectionError(connection: Connection, error: Throwable): Boolean {
        if (!ReplayConnections26.isReplay(connection)) return false
        runtime?.replayer?.onPacketFailure(error) ?: logger.warn("Afterimage replay packet failed", error)
        return true
    }

    @JvmStatic
    fun shutdown() {
        val current = runtime ?: return
        runtime = null
        runCatching { current.shutdown() }.onFailure { logger.error("Afterimage shutdown failed", it) }
    }

    private inline fun runTask(what: String, action: (AfterimageRuntime26) -> Unit) {
        val current = runtime() ?: return
        try {
            action(current)
        } catch (error: Throwable) {
            logger.error("Afterimage $what failed", error)
        }
    }
}
