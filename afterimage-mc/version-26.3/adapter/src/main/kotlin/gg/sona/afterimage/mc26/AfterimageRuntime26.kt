package gg.sona.afterimage.mc26

import gg.sona.afterimage.camera.CameraMode
import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.capture.recording.RecordingStore
import gg.sona.afterimage.clip.ClipStore
import gg.sona.afterimage.clip.export.ExportHandle
import gg.sona.afterimage.clip.export.ExportListener
import gg.sona.afterimage.clip.export.ExportQueue
import gg.sona.afterimage.clip.export.ExportState
import gg.sona.afterimage.clip.recording.combine.CombineJob
import gg.sona.afterimage.clip.recording.compact.CompactJob
import gg.sona.afterimage.clip.recording.compact.RecordingCompactor
import gg.sona.afterimage.core.log.AfterimageLog
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.*
import gg.sona.afterimage.editor.host.*
import gg.sona.afterimage.editor.imgui.EditorContext
import gg.sona.afterimage.editor.imgui.PanelLog
import gg.sona.afterimage.format.AfterimageFormat
import gg.sona.afterimage.format.AfterimageReader
import gg.sona.afterimage.mc.common.*
import gg.sona.afterimage.mc.common.taskbar.TaskbarManager
import gg.sona.afterimage.mc.common.ui.WorkspaceHost
import gg.sona.afterimage.mc26.mixin.EnchantingTableBlockEntityAccessor
import gg.sona.afterimage.mc26.mixin.EntityAccessor
import gg.sona.afterimage.mc26.mixin.FontAccessor
import gg.sona.afterimage.mc26.mixin.GameRendererAccessor
import gg.sona.afterimage.mc26.mixin.HudAccessor
import gg.sona.afterimage.mc26.mixin.ItemEntityAccessor
import gg.sona.afterimage.mc26.mixin.KeyMappingAccessor
import gg.sona.afterimage.mc26.mixin.LightmapRenderStateExtractorAccessor
import gg.sona.afterimage.mc26.mixin.ParticleAccessor
import gg.sona.afterimage.mc26.mixin.ParticleEngineAccessor
import gg.sona.afterimage.mc26.mixin.SpellParticleAccessor
import gg.sona.afterimage.mc26.mixin.TimerAccessor
import gg.sona.afterimage.mc26.render.ExportPlatform26
import gg.sona.afterimage.mc26.render.GizmoRenderer26
import gg.sona.afterimage.mc26.render.LookPreview26
import gg.sona.afterimage.mc26.render.PreviewRenderer26
import gg.sona.afterimage.mc26.render.ViewportFit26
import gg.sona.afterimage.mc26.replay.*
import gg.sona.afterimage.mc26.ui.ExportScreen26
import gg.sona.afterimage.mc26.ui.WorkspaceOverlay26
import gg.sona.afterimage.mc26.ui.WorkspaceScreens26
import gg.sona.afterimage.render.ffmpeg.FfmpegRuntime
import gg.sona.afterimage.render.look.PostProcessor
import gg.sona.afterimage.replay.session.ReplaySession
import gg.sona.afterimage.replay.source.FileReplaySource
import gg.sona.afterimage.world.EntityKind
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.SharedConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import org.lwjgl.sdl.SDLScancode
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.*
import kotlin.io.path.nameWithoutExtension
import kotlin.math.abs

class AfterimageRuntime26(val minecraft: Minecraft) : EditorHost {
    private val logger = LoggerFactory.getLogger("Afterimage")
    val platform = MinecraftPlatform26(minecraft)
    val settings = AfterimageSettings(platform.root.resolve("afterimage.properties")).also { it.load() }
    val recordings = RecordingStore(platform.recordingsDirectory)
    val projects = ProjectStore(platform.projectsDirectory)
    val clips = ClipStore(platform.clipsDirectory)
    val exports = ExportQueue()
    val recorder = RecordingController26(minecraft, recordings, clips)
    val viewportPicker = ViewportPicker26(minecraft)
    val cameraDriver = CameraDriver26(minecraft, viewportPicker)
    val replayer = ReplayController26(platform, cameraDriver)
    val workspace = WorkspaceHost(McGfx26.gfx, WorkspaceScreens26(minecraft), platform.root.resolve("afterimage-workspace.ini"))
    val keybinds = AfterimageKeybinds26(minecraft)
    val ffmpeg = FfmpegRuntime(platform.root)
    val post = PostProcessor(McGfx26.gfx, { platform.lutsDirectory }) { message, error -> logger.warn("(Afterimage) {}", message, error) }
    val exportPlatform = ExportPlatform26(minecraft)
    val sounds = SoundCapture26(minecraft).also { it.settling = { replayer.session?.settling == true } }
    val exporter = FrameExporter(exportPlatform, exports, { replayer.session }, cameraDriver, workspace, ffmpeg, post, sounds)
    val lookPreview = LookPreview26(minecraft, post)
    val packDriver = PackDriver26(minecraft)
    val gizmoRenderer = GizmoRenderer26(minecraft)
    val viewportFit = ViewportFit26(minecraft)
    private val recorderSync = RecorderSync26()
    private val spriteClock = SpriteClock26(minecraft)
    override val gizmos = GizmoBatch()
    override val version: String get() = VERSION
    override val platformLabel: String get() = "Minecraft ${SharedConstants.getCurrentVersion().name()}"
    val recordingHud = RecordingHud26(minecraft)
    private val images = ImageTextures(McGfx26.gfx)
    val screenMirror = ScreenMirror26(minecraft, cameraDriver) { replayer.session }.also { mirror -> cameraDriver.recordedHud = { mirror.recordedState() } }
    val preview = PreviewRenderer26(minecraft, cameraDriver)
    val clock = ReplayClock()
    val seekRestorer = SeekRestorer26(minecraft, exporter.sounds, clock, { beginTickEvents(it) }, { animateTick(it) }).also { replayer.seekRestorer = it }
    private val displayRandom = Random()

    val thumbnails = ThumbnailService(McGfx26.gfx, ThumbnailHost26(minecraft)) { workspace.currentWorldViewport() }
    private var thumbnailRecording: Path? = null
    private var returnToLibrary = false
    override val visuals = VisualSettings()
    val visualsController = VisualsController26(minecraft, visuals)
    private val taskbarManager = TaskbarManager()
    val context = EditorContext(this, clips, exporter)

    override val camera: CameraControl get() = cameraDriver
    override val projectsDirectory: Path get() = platform.projectsDirectory
    override val bakesDirectory: Path get() = platform.bakesDirectory
    override val exportsDirectory: Path get() = platform.exportsDirectory
    override val lutsDirectory: Path get() = platform.lutsDirectory

    override fun resourcePacks(): List<String> = packDriver.available()

    override fun activeResourcePacks(): List<String> = packDriver.active()

    override val recording: RecordingControl = object : RecordingControl {
        override fun status(): RecordingStatus {
            val stats = recorder.stats()
            return RecordingStatus(
                connected = recorder.isConnected,
                recording = recorder.isRecording,
                path = recorder.currentRecording,
                packets = stats?.packetsRecorded ?: 0L,
                bytes = stats?.bytesWritten ?: 0L,
                keyframes = stats?.keyframes ?: 0,
                overflowEvents = stats?.overflowEvents ?: 0L,
                elapsedNanos = stats?.elapsedNanos ?: 0L,
            )
        }

        override fun start(): Boolean = recorder.startRecording()

        override fun stop(): Boolean = recorder.stopRecording()

        override fun mark(label: String) = recorder.mark(label)

        override var autoRecord: Boolean
            get() = recorder.autoRecord
            set(value) {
                recorder.autoRecord = value
                settings.autoRecord = value
            }

        override var keyframeIntervalSeconds: Int
            get() = (recorder.keyframeIntervalNanos / Nanos.PER_SECOND).toInt()
            set(value) {
                recorder.keyframeIntervalNanos = Nanos.ofSeconds(value.toLong())
                settings.keyframeIntervalSeconds = value
            }

        override var showIndicator: Boolean
            get() = settings.recordingIndicator
            set(value) {
                settings.recordingIndicator = value
            }

        override var instantClipSeconds: Int
            get() = recorder.instantClipSeconds
            set(value) {
                recorder.instantClipSeconds = value.coerceIn(5, 300)
                settings.instantClipSeconds = recorder.instantClipSeconds
            }

        override fun profileIds(): List<String> = recorder.availableProfiles.map { it.id }

        override fun enabledProfiles(): Set<String> = recorder.enabledProfileIds

        override fun setProfileEnabled(id: String, enabled: Boolean) {
            val current = recorder.enabledProfileIds
            recorder.enabledProfileIds = if (enabled) current + id else current - id
        }
    }

    override val replay: ReplayControl = object : ReplayControl {
        override fun open(path: Path): Boolean = replayer.open(path)

        override fun close() {
            returnToLibrary = true
            replayer.close()
        }

        override fun isOpen(): Boolean = replayer.isReplaying

        override fun lastError(): String? = replayer.lastError

        override fun listRecordings(): List<Path> = recordings.list()

        override fun describe(path: Path): RecordingInfo? = runCatching {
            AfterimageReader.open(path).use { reader ->
                val header = reader.header
                RecordingInfo(
                    reader.durationNanos,
                    header.startEpochMillis,
                    header.metadata(AfterimageFormat.MetadataKeys.PLAYER_NAME),
                    header.metadata(AfterimageFormat.MetadataKeys.SERVER_ADDRESS),
                    header.metadata(AfterimageFormat.MetadataKeys.TITLE),
                    Files.size(path),
                )
            }
        }.getOrNull()

        override fun compact(path: Path): Boolean {
            if (replayer.path == path) return false
            if (exports.handles().any { it.state == ExportState.RUNNING || it.state == ExportState.QUEUED }) return false
            exports.submit(CompactJob(RecordingCompactor(), path))
            return true
        }

        override fun rename(path: Path, name: String): Path? {
            val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (clean.isEmpty()) return null
            if (replayer.path == path) return null
            val target = path.resolveSibling("$clean.${AfterimageFormat.EXTENSION}")
            if (Files.exists(target)) return null
            return runCatching {
                Files.move(path, target)
                val thumb = thumbnails.thumbnailPath(path)
                if (Files.exists(thumb)) Files.move(thumb, thumbnails.thumbnailPath(target))
                thumbnails.invalidate(path)
                val project = ProjectStore.forRecording(projectsDirectory, path)
                if (Files.exists(project)) Files.move(project, projectsDirectory.resolve(clean + ProjectStore.EXTENSION))
                target
            }.onFailure { logger.warn("Afterimage could not rename {}", path, it) }.getOrNull()
        }

        override fun currentPath(): Path? = replayer.path

        override fun listProjects(): List<ProjectSummary> = projects.list()

        override fun openProject(path: Path): Boolean = this@AfterimageRuntime26.openProject(path)

        override fun createProject(name: String, segments: List<Segment>): Path? = runCatching {
            val sessionId = segments.firstOrNull()?.let {
                runCatching { AfterimageReader.open(it.gameplay).use { reader -> reader.header.sessionId } }.getOrNull()
            } ?: UUID.randomUUID()
            projects.create(name, segments, sessionId).file
        }.onFailure { logger.warn("Afterimage could not create project {}", name, it) }.getOrNull()

        override fun deleteProject(path: Path): Boolean = runCatching {
            val stitched = path.resolveSibling(path.fileName.toString().removeSuffix(ProjectStore.EXTENSION).removeSuffix(ProjectStore.LEGACY_EXTENSION) + ".sequence.${AfterimageFormat.EXTENSION}")
            Files.deleteIfExists(stitched)
            Files.deleteIfExists(path)
        }.getOrDefault(false)

        override fun rebuildSequence(): Boolean {
            val session = context.session ?: return false
            val project = session.project
            if (!project.isSequence) return false
            val file = project.file ?: projects.pathFor(project.name).also { project.file = it }
            runCatching { ProjectCodec.save(project, file) }.onFailure { logger.warn("Afterimage could not save project before rebuilding", it) }
            replayer.close()
            stitchAndOpen(project)
            return true
        }

        override fun sequenceBuilding(): Boolean = stitching != null
    }

    init {
        SdlWindow.display = WindowDisplay26(minecraft)
        AfterimageLog.sink = Log4jSink26
        PanelLog.sink = { message, error -> logger.error(message, error) }
        Thread({ recordings.repairAll() }, "afterimage-repair").apply { isDaemon = true }.start()
        Thread({ if (ffmpeg.load()) ffmpeg.probeEncoders() }, "afterimage-ffmpeg").apply { isDaemon = true }.start()
        workspace.bind(context)
        recorder.autoRecord = settings.autoRecord
        replayer.matchRecordedViewDistance = settings.matchRecordedViewDistance
        replayer.applyServerPacks = settings.applyServerPacks
        recorder.instantClipSeconds = settings.instantClipSeconds.coerceIn(5, 300)
        recorder.cameraSampleRate = settings.cameraSampleRate.coerceIn(0, 1000)
        recorder.keyframeIntervalNanos = Nanos.ofSeconds(settings.keyframeIntervalSeconds.coerceIn(1, 120).toLong())
        if (settings.uiScale > 0f) workspace.requestUiScale(settings.uiScale)
        recorder.flashbackListeners.add { request, clip ->
            val session = context.session
            if (session != null && session.project.recording == clip.recording) platform.runOnGameThread { session.receive(request) }
            platform.runOnGameThread { message("Flashback: ${clip.title}") }
        }
        replayer.onOpened = ::onReplayOpened
        exporter.focusDistance = { nanos, pose -> context.session?.focusDistanceAt(nanos, pose.position) ?: 8.0 }
        exporter.projectionScale = { viewportPicker.tanHalfFov() }
        lookPreview.viewport = { workspace.currentWorldViewport() ?: intArrayOf(0, 0, minecraft.window.width, minecraft.window.height) }
        lookPreview.request = { lookPreviewRequest() }
        gizmoRenderer.viewport = { workspace.currentWorldViewport() }
        gizmoRenderer.viewProjection = { viewportPicker.projection.viewProjection(it) }
        viewportFit.viewport = { workspace.currentWorldViewport() }
        viewportFit.enabled = { replayer.isReplaying && workspace.isOpen && !exporter.isExporting && !preview.rendering }
        visualsController.alphaExport = { exporter.transparentExport }
        preview.visuals = visualsController
        replayer.onWorldTick = { boundary -> worldTick(boundary) }
        replayer.onSeek = { position -> if (minecraft.level != null) spriteClock.align(Math.floorDiv(position, TICK_NANOS)) }
        replayer.onWorldReady = { reopen -> if (reopen) workspace.open() }
        replayer.isWorkspaceOpen = { workspace.isOpen }
        workspace.onViewportScroll = { amount ->
            if (cameraDriver.settings.mode == CameraMode.FREE && !cameraDriver.pathActive) {
                cameraDriver.scene.scroll(amount)
            } else {
                val settings = cameraDriver.settings
                settings.freeSpeed = (settings.freeSpeed * Math.pow(1.2, amount.toDouble())).coerceIn(0.5, 200.0)
            }
        }
        cameraDriver.scene.viewport = { workspace.currentWorldViewport() }
        workspace.onFilesDropped = { files -> platform.runOnGameThread { openDropped(files) } }
        cameraDriver.scene.lockCursor = { x, y -> workspace.lockCursor(x, y) }
        cameraDriver.scene.unlockCursor = { workspace.unlockCursor() }
        replayer.onClosed = ::onReplayClosed
        gizmoRenderer.batch = { gizmos }
        gizmoRenderer.enabled = { replayer.isReplaying && workspace.isOpen && !exporter.isExporting }
        visualsController.active = { replayer.isReplaying }
        viewportPicker.recorderEntityId = { replayer.session?.shadow26?.localPlayer?.entityId ?: Int.MIN_VALUE }
        viewportPicker.recorderName = { replayer.session?.shadow26?.localPlayer?.name }
        viewportPicker.hidden = { cameraDriver.shouldHide(it) || visualsController.shouldHide(it) }
        visualsController.exporting = { exporter.isExporting }
        replayer.timeOverride = { visualsController.timeOverride() }
        exporter.onExportStarted = {
            clock.reset()
            minecraft.gui.setScreen(ExportScreen26 { cancelRunningExports() })
        }
        exporter.onExportFinished = {
            if (minecraft.gui.screen() is ExportScreen26) gg.sona.afterimage.mc26.ui.ScreenGuard26.allow { minecraft.gui.setScreen(null) }
            if (replayer.isReplaying) workspace.open()
        }
        visualsController.timeOfDayProvider = { context.session?.let { s -> s.replay?.let { r -> s.valueAt(ValueLane.TIME_OF_DAY, r.positionNanos) } } }
        visualsController.mobTypeProvider = { id -> context.session?.replay?.shadow26?.entityMap?.get(id)?.takeIf { it.kind == EntityKind.MOB }?.type }
        keybinds.register()
    }

    override fun message(text: String) = hudMessage(text)

    override fun later(action: () -> Unit) = platform.runOnGameThread(action)

    override fun preference(key: String): String? = settings[key]

    override fun setPreference(key: String, value: String) {
        settings[key] = value
        if (key == "replay.matchRecordedViewDistance") replayer.matchRecordedViewDistance = settings.matchRecordedViewDistance
        if (key == "replay.serverResourcePacks") replayer.applyServerPacks = settings.applyServerPacks
    }

    override var uiScale: Float
        get() = workspace.uiScale
        set(value) {
            workspace.requestUiScale(value)
            settings.uiScale = value
        }

    override fun pickEntity(normalizedX: Float, normalizedY: Float): ViewportPick? = if (replayer.isReplaying) viewportPicker.pick(normalizedX, normalizedY) else null

    override fun project(x: Double, y: Double, z: Double): FloatArray? = if (replayer.isReplaying) viewportPicker.project(x, y, z) else null

    override fun ray(normalizedX: Float, normalizedY: Float): DoubleArray? {
        if (!replayer.isReplaying) return null
        val (origin, direction) = viewportPicker.worldRay(normalizedX, normalizedY) ?: return null
        return doubleArrayOf(origin.x, origin.y, origin.z, direction.x, direction.y, direction.z)
    }

    override fun thumbnail(recording: Path): Int? = thumbnails.texture(recording)

    override fun imageTexture(resource: String): IntArray? = images.texture(resource)

    override fun requestPreview(pose: CameraPose?, width: Int, height: Int) = preview.request(pose, width, height)

    override fun previewTexture(): IntArray? = if (preview.textureId != 0) intArrayOf(preview.textureId, preview.width, preview.height) else null

    override fun worldTimeOfDay(): Long? = minecraft.level?.let { Math.floorMod(it.defaultClockTime, 24000L) }

    override fun captureThumbnail(recording: Path) = thumbnails.request(recording, 1)

    fun onTimerAdvanced(timer: TimerAccessor) {
        val exporting = exporter.isExporting
        if (exporting) exporter.onTimerAdvanced() else replayer.advancePlayback()
        val replay = replayer.session
        if (replay == null) {
            clock.reset()
            return
        }
        val partial = clock.apply(replay.positionNanos, replay.lastTickNanos)
        timer.afterimage_setDeltaTickResidual(partial)
        eventNanos = replay.positionNanos
        eventCounter = 0
        reseed(DeterministicRandom.mix(replay.positionNanos, FRAME_SALT))
    }

    @Volatile
    private var eventNanos = 0L
    private var eventCounter = 0L

    fun beginTickEvents(boundaryNanos: Long) {
        eventNanos = boundaryNanos
        eventCounter = 0
        reseed(DeterministicRandom.mix(boundaryNanos, TICK_SALT))
    }

    fun worldTick(boundaryNanos: Long) {
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        eventNanos = boundaryNanos
        eventCounter = 0
        reseed(DeterministicRandom.mix(boundaryNanos, TICK_SALT), entities = true)
        clock.manualTick = true
        try {
            minecraft.gui.hud.tick(false)
            screenMirror.tick()
            spriteClock.align(Math.floorDiv(boundaryNanos, TICK_NANOS))
            minecraft.gameRenderer.tick()
            level.tickEntities()
            replayer.session?.let { recorderSync.afterTick(it, level) }
            level.tickBlockEntities()
            level.tick { true }
            animateTick(boundaryNanos)
            minecraft.particleEngine.tick()
            visualsController.onTick()
            cameraDriver.onClientTick()
        } catch (error: Throwable) {
            logger.error("Afterimage world tick failed", error)
        } finally {
            clock.manualTick = false
        }
    }

    var animateSeed: Long = 0L
        private set

    fun animateOrigin(): net.minecraft.world.entity.Entity? {
        val level = minecraft.level ?: return null
        val recorder = replayer.session?.shadow26?.localPlayer?.entityId?.let { level.getEntity(it) }
        return recorder ?: minecraft.cameraEntity ?: minecraft.player
    }

    fun animateTick(boundaryNanos: Long) {
        val level = minecraft.level ?: return
        val origin = animateOrigin() ?: return
        eventNanos = boundaryNanos
        eventCounter = ANIMATE_EVENTS
        animateSeed = DeterministicRandom.mix(boundaryNanos, ANIMATE_POSITION_SALT)
        reseed(DeterministicRandom.mix(boundaryNanos, ANIMATE_SALT))
        level.animateTick(origin.blockX, origin.blockY, origin.blockZ)
    }

    fun reseedForPacket(timestampNanos: Long) {
        if (timestampNanos != eventNanos) {
            eventNanos = timestampNanos
            eventCounter = 0
        }
        reseed(DeterministicRandom.mix(timestampNanos, PACKET_SALT + eventCounter++))
    }

    fun onEntityCreated(entity: net.minecraft.world.entity.Entity) {
        if (!replayer.isReplaying) return
        (entity as EntityAccessor).afterimage_random().setSeed(DeterministicRandom.mix(eventNanos, ENTITY_SALT + eventCounter++))
    }

    fun onEntitySpawned(id: Int) {
        val replay = replayer.session ?: return
        val entity = minecraft.level?.getEntity(id) ?: return
        val spawnedAt = replay.shadow26.entityMap[id]?.spawnedAtNanos ?: eventNanos
        val random = (entity as EntityAccessor).afterimage_random()
        random.setSeed(DeterministicRandom.mix(spawnedAt, ENTITY_SALT + id))
        if (entity is net.minecraft.world.entity.item.ItemEntity) (entity as ItemEntityAccessor).afterimage_setBobOffs(random.nextFloat() * Math.PI.toFloat() * 2f)
    }

    fun onParticleCreated(particle: net.minecraft.client.particle.Particle) {
        if (!replayer.isReplaying) return
        (particle as ParticleAccessor).afterimage_random().setSeed(DeterministicRandom.mix(eventNanos, PARTICLE_SALT + eventCounter++))
    }

    fun displayTickRandom(): Random = if (replayer.isReplaying) displayRandom else Random()

    private fun reseed(seed: Long, entities: Boolean = false) {
        DeterministicRandom.reseedMath(seed)
        val level = minecraft.level ?: return
        level.random.setSeed(seed)
        (minecraft.particleEngine as ParticleEngineAccessor).afterimage_random().setSeed(seed xor 1L)
        (minecraft.gameRenderer as GameRendererAccessor).afterimage_lightmapExtractor().let { (it as LightmapRenderStateExtractorAccessor).afterimage_random().setSeed(seed xor 2L) }
        (minecraft.gui.hud as HudAccessor).afterimage_random().setSeed(seed xor 3L)
        (minecraft.font as FontAccessor).afterimage_random().setSeed(seed xor 4L)
        SpellParticleAccessor.afterimage_random().setSeed(seed xor 6L)
        EnchantingTableBlockEntityAccessor.afterimage_random().setSeed(seed xor 7L)
        displayRandom.setSeed(seed xor 8L)
        AfterimageHooks26.reseedVisualRandoms(seed)
        if (entities) {
            for (entity in level.entitiesForRendering()) {
                (entity as EntityAccessor).afterimage_random().setSeed(DeterministicRandom.mix(seed, entity.id.toLong()))
            }
        }
    }

    fun onFrameStart(tickDelta: Float) {
        if (preview.rendering) return
        exporter.onFrameStart()
        if (replayer.isReplaying && (exporter.isExporting || replayer.syncChunkFrames > 0)) minecraft.level?.let { LightSync26.drain(it) }
        applyPackTrack()
        exporter.beginRender()
        applySpeedTrack()
        visualsController.onFrame()
        taskbarManager.onFrame(exporter.live())
        replayer.onFrame(tickDelta, workspace.isOpen, workspace.viewportHovered, !workspace.textInputActive)
    }

    private fun applyPackTrack() {
        val session = context.session ?: return
        val replay = session.replay ?: return
        packDriver.apply(session.project.packAt(replay.positionNanos))
    }

    private fun lookPreviewRequest(): LookPreview26.Request? {
        if (exporter.isExporting || !context.ui.lookPreview || minecraft.level == null) return null
        val session = context.session ?: return null
        val replay = session.replay ?: return null
        val look = session.project.look
        if (!look.active) return null
        val pose = cameraDriver.currentPose()
        return LookPreview26.Request(look, session.focusDistanceAt(replay.positionNanos, pose.position), false, viewportPicker.tanHalfFov(), (replay.positionNanos / PREVIEW_GRAIN_NANOS % 4096L).toFloat())
    }

    private var lastAppliedView: ViewState? = null

    private fun applyViewTrack() {
        val session = context.session ?: return
        val replay = session.replay ?: return
        val view = session.project.viewAt(replay.positionNanos)
        if (view == null) {
            lastAppliedView = null
            return
        }
        if (view == lastAppliedView) return
        val previous = lastAppliedView
        val discreteChanged = previous == null || previous.mode != view.mode || previous.targetEntityId != view.targetEntityId || previous.bodyPart != view.bodyPart
        lastAppliedView = view
        view.applyTo(cameraDriver.settings)
        if (discreteChanged) cameraDriver.apply() else cameraDriver.updateViewNumeric(view)
    }

    private var freezeUntilNanos = 0L
    private var frozenAtNanos = -1L
    private var lastPlaybackNanos = -1L

    private fun applyFreezeTrack(session: EditorSession, replay: ReplaySession) {
        val now = System.nanoTime()
        if (frozenAtNanos >= 0L) {
            if (replay.playing || replay.positionNanos != frozenAtNanos) {
                frozenAtNanos = -1L
                lastPlaybackNanos = replay.positionNanos
                return
            }
            if (now >= freezeUntilNanos) {
                frozenAtNanos = -1L
                replay.seek(replay.positionNanos + Nanos.PER_MILLI)
                replay.play()
            }
            return
        }
        if (!replay.playing || replay.speed <= 0.0 || !session.project.laneEnabled(LaneKind.FREEZE)) {
            lastPlaybackNanos = replay.positionNanos
            return
        }
        val previous = lastPlaybackNanos
        val current = replay.positionNanos
        lastPlaybackNanos = current
        if (previous < 0L || current < previous) return
        val crossed = session.project.freeze.keyframes.firstOrNull { it.timeNanos > previous && it.timeNanos <= current } ?: return
        replay.seek(crossed.timeNanos)
        replay.pause()
        frozenAtNanos = crossed.timeNanos
        freezeUntilNanos = now + (crossed.value * Nanos.PER_SECOND).toLong()
        context.status("Freeze ${String.format("%.1f", crossed.value)} s")
    }

    override fun keybindings(): List<KeybindInfo> = keybinds.all.map { binding ->
        val key = (binding as KeyMappingAccessor).afterimage_key()
        KeybindInfo(binding.name, keybinds.labels[binding.name] ?: binding.name, key.value, binding.translatedKeyMessage.string, binding.isDefault)
    }

    override fun captureNextKeyDown(): Int? {
        for (code in 0 until SDLScancode.SDL_SCANCODE_COUNT) {
            if (code == SDLScancode.SDL_SCANCODE_ESCAPE) continue
            if (SdlWindow.keyDown(code)) return code
        }
        return null
    }

    override fun setKeybinding(name: String, keyCode: Int) {
        val binding = keybinds.all.firstOrNull { it.name == name } ?: return
        binding.setKey(InputConstants.Type.KEYBOARD.getOrCreate(keyCode))
        KeyMapping.resetMapping()
        minecraft.options.save()
    }

    override fun resetKeybinding(name: String) {
        val binding = keybinds.all.firstOrNull { it.name == name } ?: return
        binding.setKey(binding.defaultKey)
        KeyMapping.resetMapping()
        minecraft.options.save()
    }

    private fun applySpeedTrack() {
        applyViewTrack()
        val session = context.session ?: return
        val replay = session.replay ?: return
        if (!exporter.isExporting) applyFreezeTrack(session, replay)
        if (!replay.playing || exporter.isExporting) return
        val value = session.speedAt(replay.positionNanos) ?: return
        val target = if (replay.speed < 0) -value else value
        if (abs(replay.speed - target) > 1e-6) replay.speed = target
    }

    fun cancelRunningExports() {
        exports.handles().filter { it.state == ExportState.RUNNING }.forEach { it.cancel() }
    }

    fun onWorldRendered() {
        if (preview.rendering) return
        exporter.onWorldPassEnd()
        if (!exporter.isExporting) lookPreview.onWorldPassEnd()
        gizmoRenderer.render(minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState)
    }

    fun onLevelRendered() {
        exporter.onFrameEnd()
        thumbnails.onWorldRendered()
    }

    fun onFrameEnd() {
        if (workspace.rendering) return
        recorder.camera.onFrameEnd()
        recorder.onFrame()
        replayer.onFrameRendered()
        if (exporter.isExporting) workspace.renderExport() else WorkspaceOverlay26.drawAfterGui()
        preview.onFrameEnd(exporter.isExporting)
        McGfx26.gfx.endFrame()
    }

    private var autoOpenTicks = if (System.getenv("AFTERIMAGE_AUTO_OPEN").isNullOrBlank()) -1 else 0

    private fun autoOpen() {
        if (autoOpenTicks < 0) return
        if (minecraft.gui.screen() !is TitleScreen) return
        if (++autoOpenTicks < 30) return
        autoOpenTicks = -1
        val requested = System.getenv("AFTERIMAGE_AUTO_OPEN") ?: return
        val target = if (requested == "latest") recordings.list().maxByOrNull { it.fileName.toString() } else Paths.get(requested)
        if (target != null) replayer.open(target)
    }

    fun onClientTickStart() {
        recorder.onClientTickStart()
    }

    fun onClientTick() {
        handleKeys()
        autoOpen()
        settings.saveIfDirty()
        val recording = if (recorder.isRecording) recorder.currentRecording else null
        if (recording != null && recording != thumbnailRecording) {
            thumbnailRecording = recording
            thumbnails.request(recording, THUMBNAIL_DELAY_FRAMES)
        }
        recorder.onClientTick()
        replayer.onClientTick()
    }

    fun shutdown() {
        recorder.shutdown()
        replayer.close()
        exports.close()
        visualsController.shutdown()
        thumbnails.shutdown()
        images.shutdown()
        workspace.shutdown()
        gizmoRenderer.release()
        post.destroy()
        taskbarManager.shutdown()
        McGfx26.shutdown()
    }

    private fun handleKeys() {
        while (keybinds.toggleRecording.consumeClick()) {
            if (recorder.isRecording) {
                recorder.stopRecording()
                hudMessage("Recording stopped")
            } else if (recorder.startRecording()) {
                hudMessage("Recording started")
            }
        }
        while (keybinds.marker.consumeClick()) {
            recorder.mark("manual")
            hudMessage("Marker added")
        }
        while (keybinds.instantClip.consumeClick()) {
            val clip = recorder.instantClip()
            hudMessage(if (clip != null) "Saved the last ${recorder.instantClipSeconds} s as a clip" else "Not recording, nothing to clip")
        }
        while (keybinds.workspace.consumeClick()) workspace.toggle()
        val session = replayer.session
        if (session != null) {
            while (keybinds.playPause.consumeClick()) session.togglePlaying()
            while (keybinds.seekBack.consumeClick()) session.seekRelative(-Nanos.ofSeconds(5))
            while (keybinds.seekForward.consumeClick()) session.seekRelative(Nanos.ofSeconds(5))
            while (keybinds.slower.consumeClick()) session.speed = session.speed / 2.0
            while (keybinds.faster.consumeClick()) session.speed = session.speed * 2.0
            while (keybinds.cameraMode.consumeClick()) {
                val modes = CameraMode.entries
                cameraDriver.settings.mode = modes[(cameraDriver.settings.mode.ordinal + 1) % modes.size]
                cameraDriver.apply()
                hudMessage("Camera: ${cameraDriver.settings.mode.label}")
            }
            while (keybinds.keyframe.consumeClick()) {
                context.session?.keyframeAtPlayhead(cameraDriver.currentPose())
                hudMessage("Keyframe at ${session.positionNanos / Nanos.PER_MILLI} ms")
            }
        } else {
            for (binding in listOf(keybinds.playPause, keybinds.seekBack, keybinds.seekForward, keybinds.slower, keybinds.faster, keybinds.cameraMode, keybinds.keyframe)) {
                while (binding.consumeClick()) continue
            }
        }
    }

    private var pendingProject: EditorProject? = null

    @Volatile
    private var stitching: EditorProject? = null

    fun openProject(path: Path): Boolean {
        val project = runCatching { ProjectCodec.load(path) }.onFailure { logger.warn("Afterimage could not load project {}", path, it) }.getOrNull() ?: return false
        if (minecraft.level != null && !replayer.isReplaying) return false
        if (replayer.isReplaying) replayer.close()
        if (!project.isSequence) {
            val gameplay = project.segments.firstOrNull()?.gameplay ?: project.recording
            pendingProject = project
            if (!replayer.open(gameplay)) {
                pendingProject = null
                return false
            }
            return true
        }
        val stitched = projects.stitchedPathFor(project)
        if (!project.sequenceStale && Files.exists(stitched)) {
            project.recording = stitched
            pendingProject = project
            if (!replayer.open(stitched)) {
                pendingProject = null
                return false
            }
            return true
        }
        stitchAndOpen(project)
        return true
    }

    private fun stitchAndOpen(project: EditorProject) {
        if (stitching != null) return
        stitching = project
        val output = projects.stitchedPathFor(project)
        val job = projects.stitchJob(project, output) as CombineJob
        val submittedHandle = exports.submit(job)
        exports.listeners.add(object : ExportListener {
            override fun onStateChanged(handle: ExportHandle) {
                if (handle !== submittedHandle) return
                if (handle.state == ExportState.RUNNING || handle.state == ExportState.QUEUED) return
                exports.listeners.remove(this)
                platform.runOnGameThread {
                    stitching = null
                    if (handle.state != ExportState.DONE) {
                        message("Could not build the sequence: ${handle.failure?.message ?: handle.state.name.lowercase()}")
                        return@runOnGameThread
                    }
                    val lengths = job.result?.segmentLengths ?: emptyList()
                    for ((index, segment) in project.segments.withIndex()) {
                        lengths.getOrNull(index)?.let { project.segments[index] = segment.copy(lengthNanos = it) }
                    }
                    project.sequenceKey = project.currentSequenceKey()
                    project.recording = output
                    project.file?.let { runCatching { ProjectCodec.save(project, it) } }
                    if (minecraft.level == null) {
                        pendingProject = project
                        if (!replayer.open(output)) pendingProject = null
                    } else {
                        message("Sequence built, open ${project.name} from the library")
                    }
                }
            }
        })
        message("Building sequence for ${project.name}")
    }

    private fun onReplayOpened(path: Path) {
        val session = replayer.session ?: return
        val pending = pendingProject
        pendingProject = null
        val project = pending?.also { it.recording = path } ?: loadProject(path) ?: EditorProject(UUID.randomUUID(), path.nameWithoutExtension, path, session.source.header.sessionId)
        if (project.segments.isEmpty()) project.segments += Segment(path, 0L, 0L, session.durationNanos)
        for (clip in clips.forRecording(path)) if (project.clip(clip.id) == null) project.clips += clip
        val editor = EditorSession(project, session)
        context.session = editor
        cameraDriver.path = project.camera
        cameraDriver.pathProvider = { nanos -> context.session?.pathPoseAt(nanos) }
        cameraDriver.fovProvider = { nanos -> context.session?.valueAt(ValueLane.FOV, nanos) }
        cameraDriver.shakeProvider = { nanos -> context.session?.valueAt(ValueLane.SHAKE, nanos) }
        cameraDriver.shakeFrequencyProvider = { nanos -> context.session?.valueAt(ValueLane.SHAKE_FREQUENCY, nanos) }
        context.status("Opened ${path.fileName}")
        workspace.open()
        if (!Files.exists(thumbnails.thumbnailPath(path))) thumbnails.request(path, THUMBNAIL_DELAY_FRAMES)
        Thread({
            runCatching { FileReplaySource.open(path).use { editor.events.build(path, it, session.shadow26.recorderIdentity) } }
                .onFailure { logger.warn("Afterimage could not index events for {}", path.fileName, it) }
        }, "afterimage-events").apply { isDaemon = true }.start()
    }

    private fun openDropped(files: List<String>) {
        val recording = files.map { Paths.get(it) }.firstOrNull { AfterimageFormat.isRecording(it.fileName.toString()) && Files.isRegularFile(it) }
        if (recording == null) {
            message("Drop a .${AfterimageFormat.EXTENSION} file to open it")
            return
        }
        val target = if (recording.toAbsolutePath().parent == recordings.directory.toAbsolutePath()) recording else {
            val copy = recordings.directory.resolve(recording.fileName)
            runCatching { Files.copy(recording, copy, StandardCopyOption.REPLACE_EXISTING) }.onFailure { logger.warn("Afterimage could not import {}", recording, it) }
            copy
        }
        if (minecraft.level != null && !replayer.isReplaying) {
            message("Imported ${target.fileName}: leave the world to open it from the library")
            return
        }
        if (replayer.isReplaying) replayer.close()
        if (!replayer.open(target)) message(replayer.lastError ?: "Could not open ${target.fileName}")
    }

    private fun onReplayClosed() {
        screenMirror.close()
        packDriver.restore()
        lookPreview.release()
        context.session?.events?.cancel()
        preview.release()
        if (returnToLibrary) {
            returnToLibrary = false
            platform.runOnGameThread { if (minecraft.level == null && !workspace.isOpen) workspace.open() }
        }
        context.session?.let { session ->
            if (session.project.dirty) runCatching { ProjectCodec.save(session.project, session.project.file ?: projectPath(session.project.recording)) }
        }
        context.session = null
        cameraDriver.path = null
    }

    private fun loadProject(recording: Path): EditorProject? {
        val path = projectPath(recording)
        if (!Files.exists(path)) return null
        return runCatching { ProjectCodec.load(path) }.onFailure { logger.warn("Afterimage could not load project {}", path, it) }.getOrNull()
    }

    private fun projectPath(recording: Path): Path = ProjectStore.forRecording(platform.projectsDirectory, recording)

    private fun hudMessage(message: String) {
        minecraft.player?.sendSystemMessage(Component.literal("[Afterimage] $message"))
        logger.info(message)
    }

    companion object {
        const val TICK_NANOS = 50_000_000L
        const val FRAME_SALT = 0x11L
        const val TICK_SALT = 0x22L
        const val PACKET_SALT = 0x33L
        const val ENTITY_SALT = 0x44L
        const val PARTICLE_SALT = 0x55L
        const val ANIMATE_SALT = 0x66L
        const val ANIMATE_EVENTS = 1_000_000L
        const val ANIMATE_POSITION_SALT = 0x77L
        const val THUMBNAIL_DELAY_FRAMES = 90
        const val PREVIEW_GRAIN_NANOS = 16_666_667L
        const val VERSION = "1.0.0"
    }
}
