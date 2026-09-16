package gg.sona.afterimage.mc263.render

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.mc263.McGfx
import gg.sona.afterimage.mc263.McGfx.makeOpaque
import gg.sona.afterimage.mc263.gfx.RpTexture
import gg.sona.afterimage.mc263.mixin.CameraAccessor
import gg.sona.afterimage.mc263.mixin.CloudRendererAccessor
import gg.sona.afterimage.mc263.mixin.GameRendererAccessor
import gg.sona.afterimage.mc263.mixin.LevelExtractorAccessor
import gg.sona.afterimage.mc263.mixin.LevelRendererAccessor
import gg.sona.afterimage.mc263.mixin.SectionOcclusionGraphAccessor
import gg.sona.afterimage.mc263.replay.CameraDriver
import gg.sona.afterimage.mc263.replay.ReplayCameraEntity
import gg.sona.afterimage.mc263.replay.VisualsController
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.TextureFilteringMethod
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.CloudRenderer
import net.minecraft.client.renderer.GlobalSettingsUniform
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.SectionOcclusionGraph
import net.minecraft.client.renderer.SkyRenderer
import net.minecraft.client.renderer.ViewArea
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.extract.LevelExtractor
import net.minecraft.client.renderer.fog.FogRenderer
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState
import net.minecraft.client.renderer.state.level.LevelRenderState
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.ChunkPos
import org.joml.Matrix4f
import org.slf4j.LoggerFactory
import kotlin.math.max

class PreviewRenderer(private val minecraft: Minecraft, private val camera: CameraDriver) {
    private val logger = LoggerFactory.getLogger("Afterimage")
    private val gfx = McGfx.gfx
    var visuals: VisualsController? = null

    private var requestedPose: CameraPose? = null
    private var requestedWidth = 0
    private var requestedHeight = 0
    private var requestFrame = 0L
    private var frame = 0L

    private var target: RenderTarget? = null
    private var color: RpTexture? = null
    private var sky: SkyRenderer? = null
    private var clouds: CloudRenderer? = null
    private var fog: FogRenderer? = null
    private var projectionBuffer: ProjectionMatrixBuffer? = null
    private var globals: GlobalSettingsUniform? = null
    private var state: LevelRenderState? = null
    private var extractor: LevelExtractor? = null
    private var previewCamera: Camera? = null
    private var entity: ReplayCameraEntity? = null
    private var level: ClientLevel? = null

    @Volatile
    private var graph: SectionOcclusionGraph? = null

    @Volatile
    private var mainGraph: SectionOcclusionGraph? = null
    private var viewArea: ViewArea? = null
    private var viewCenter: SectionPos? = null
    private val visibleSections = ObjectArrayList<SectionRenderDispatcher.RenderSection>(10000)
    private val nearbyVisibleSections = ObjectArrayList<SectionRenderDispatcher.RenderSection>(50)
    private val addedLoaded = LongOpenHashSet()
    private val removedLoaded = LongOpenHashSet()
    private val addedEmpty = LongOpenHashSet()
    private val removedEmpty = LongOpenHashSet()
    private val loading = ChunkLoadingRenderState()

    var rendering = false
        private set
    var textureId = 0
        private set
    var width = 0
        private set
    var height = 0
        private set

    fun owns(candidate: LevelExtractor): Boolean = candidate === extractor

    fun request(pose: CameraPose?, width: Int, height: Int) {
        requestedPose = pose
        requestedWidth = width.coerceIn(16, 2048)
        requestedHeight = height.coerceIn(16, 2048)
        requestFrame = frame
    }

    fun onMainExtracted(consumed: ChunkLoadingRenderState) {
        if (graph == null) return
        merge(addedLoaded, removedLoaded, consumed.addedLoadedChunks, consumed.removedLoadedChunks)
        merge(addedEmpty, removedEmpty, consumed.addedEmptySections, consumed.removedEmptySections)
    }

    fun onPropagationScheduled(source: SectionOcclusionGraph, section: SectionRenderDispatcher.RenderSection) {
        val preview = graph ?: return
        if (source !== mainGraph) return
        if ((preview as SectionOcclusionGraphAccessor).afterimage_currentGraph().get() == null) return
        runCatching { preview.schedulePropagationFrom(section) }
    }

    fun onFrameEnd(exporting: Boolean) {
        frame++
        if (rendering || exporting) return
        val pose = requestedPose ?: return releaseIfIdle()
        if (frame - requestFrame > 2) return releaseIfIdle()
        val level = minecraft.level ?: return release()
        if (minecraft.player == null) return
        val renderer = minecraft.levelRenderer
        val area = renderer.viewArea() ?: return
        val accessor = renderer as LevelRendererAccessor
        if (accessor.afterimage_sectionRenderDispatcher() == null) return
        if (!minecraft.gameRenderer.mainCamera().isInitialized) return
        val output = ensureTarget(requestedWidth, requestedHeight)
        val state = ensureState(level, renderer)
        val extractor = extractor ?: return
        val previewCamera = previewCamera ?: return
        val fog = fog ?: return
        val projectionBuffer = projectionBuffer ?: return
        val globals = globals ?: return
        val graph = graph ?: return
        val previewEntity = entity?.takeIf { it.level() === level } ?: ReplayCameraEntity(level).also { entity = it }
        syncGraph(renderer, area, graph)
        rendering = true
        try {
            camera.previewPose = pose
            previewEntity.place(pose)
            previewCamera.setLevel(level)
            previewCamera.setEntity(previewEntity)
            val tracker = minecraft.deltaTracker
            val partial = tracker.getGameTimeDeltaPartialTick(false)
            previewCamera.tick()
            previewCamera.update(tracker)
            val cameraAccess = previewCamera as CameraAccessor
            val aspect = requestedWidth.toFloat() / requestedHeight
            val zeroToOne = RenderSystem.getDevice().deviceInfo.isZZeroToOne
            val depthFar = cameraAccess.afterimage_depthFar()
            val cullFov = max(previewCamera.fov, minecraft.options.fov().get().toFloat())
            val cullProjection = Matrix4f().perspective(Math.toRadians(cullFov.toDouble()).toFloat(), aspect, 0.05f, depthFar, zeroToOne)
            val position = previewCamera.position()
            val frustum = Frustum(previewCamera.getViewRotationMatrix(Matrix4f()), cullProjection)
            frustum.prepare(position.x, position.y, position.z)
            cameraAccess.afterimage_setCullFrustum(frustum)
            val cameraState = state.cameraRenderState
            previewCamera.extractRenderState(cameraState, tracker)
            cameraState.projectionMatrix.setPerspective(Math.toRadians(previewCamera.fov.toDouble()).toFloat(), aspect, depthFar, 0.05f, zeroToOne)
            if (cameraState.smartCull && level.getBlockState(BlockPos.containing(position)).isSolidRender) cameraState.smartCull = false
            cameraState.fogType = previewCamera.fluidInCamera
            cameraState.fogData = fog.setupFog(previewCamera, minecraft.options.effectiveRenderDistance, tracker, 0f, level)
            val extractorAccess = extractor as LevelExtractorAccessor
            extractorAccess.afterimage_setLastViewDistance(minecraft.options.effectiveRenderDistance)
            extractorAccess.afterimage_setPrevCamRotX(Double.MIN_VALUE)
            extractorAccess.afterimage_setPrevCamRotY(Double.MIN_VALUE)
            val gameRenderer = minecraft.gameRenderer as GameRendererAccessor
            val mainState = accessor.afterimage_levelRenderState()
            val mainVisible = renderer.visibleSections()
            val mainNearby = renderer.nearbyVisibleSections()
            val mainClouds = renderer.cloudRenderer()
            val mainSky = accessor.afterimage_skyRenderer()
            val mainTarget = minecraft.gameRenderer.mainRenderTarget()
            val mainGlobals = RenderSystem.getGlobalSettingsUniform()
            accessor.afterimage_setLevelRenderState(state)
            accessor.afterimage_setSectionOcclusionGraph(graph)
            accessor.afterimage_setVisibleSections(visibleSections)
            accessor.afterimage_setNearbyVisibleSections(nearbyVisibleSections)
            accessor.afterimage_setCloudRenderer(clouds)
            accessor.afterimage_setSkyRenderer(sky)
            gameRenderer.afterimage_setMainRenderTarget(output)
            try {
                extractor.extract(tracker, previewCamera, partial)
                state.playerCompiledSectionCallback = null
                state.shouldShowEntityOutlines = false
                state.shouldResetSkyRenderer = false
                val visuals = visuals
                if (visuals?.hideClouds() == true) state.cloudColor = 0
                if (visuals?.hideWeather() == true) state.weatherRenderState.reset()
                fillChunkLoading(graph, level)
                val options = minecraft.gameRenderer.gameRenderState().optionsRenderState
                globals.update(
                    requestedWidth,
                    requestedHeight,
                    options.glintStrength,
                    state.gameTime,
                    state.worldPartialTicks,
                    options.menuBackgroundBlurriness,
                    cameraState.pos,
                    options.textureFiltering == TextureFilteringMethod.RGSS,
                )
                RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(Matrix4f(cameraState.projectionMatrix)), ProjectionType.PERSPECTIVE)
                fog.updateBuffer(cameraState.fogData)
                val terrainFog = fog.getBuffer(FogRenderer.FogMode.WORLD)
                val fogColor = visuals?.clearColor(cameraState.fogData.color) ?: cameraState.fogData.color
                renderer.render(gameRenderer.afterimage_resourcePool(), false, cameraState, terrainFog, fogColor, visuals?.hideSky() != true, false)
                RenderSystem.setShaderFog(fog.getBuffer(FogRenderer.FogMode.NONE))
            } finally {
                sky = accessor.afterimage_skyRenderer()
                accessor.afterimage_setLevelRenderState(mainState)
                accessor.afterimage_setSectionOcclusionGraph(mainGraph)
                accessor.afterimage_setVisibleSections(mainVisible)
                accessor.afterimage_setNearbyVisibleSections(mainNearby)
                accessor.afterimage_setCloudRenderer(mainClouds)
                accessor.afterimage_setSkyRenderer(mainSky)
                gameRenderer.afterimage_setMainRenderTarget(mainTarget)
                if (mainGlobals != null) RenderSystem.setGlobalSettingsUniform(mainGlobals)
                clearPending()
                fog.endFrame()
                clouds?.endFrame()
            }
            output.makeOpaque()
            textureId = color?.handle ?: 0
            width = requestedWidth
            height = requestedHeight
        } catch (error: Throwable) {
            logger.warn("Afterimage preview render failed", error)
            textureId = 0
        } finally {
            camera.previewPose = null
            rendering = false
        }
    }

    private fun ensureState(level: ClientLevel, renderer: LevelRenderer): LevelRenderState {
        var state = this.state
        if (state == null) {
            state = LevelRenderState()
            state.chunkLoadingRenderState = loading
            this.state = state
            extractor = LevelExtractor(minecraft, state, renderer)
            previewCamera = Camera()
            fog = FogRenderer()
            projectionBuffer = ProjectionMatrixBuffer("afterimage preview")
            globals = GlobalSettingsUniform()
            clouds = CloudRenderer()
            mainGraph = renderer.sectionOcclusionGraph()
            graph = SectionOcclusionGraph()
        }
        if (this.level !== level) {
            this.level = level
            (extractor as LevelExtractorAccessor).afterimage_setLevel(level)
            previewCamera?.setLevel(level)
            entity = null
            viewArea = null
        }
        val cloudTexture = (renderer.cloudRenderer() as CloudRendererAccessor).afterimage_texture()
        clouds?.let { (it as CloudRendererAccessor).let { access -> if (access.afterimage_texture() !== cloudTexture) access.afterimage_setTexture(cloudTexture) } }
        return state
    }

    private fun ensureTarget(width: Int, height: Int): RenderTarget {
        target?.takeIf { it.width == width && it.height == height }?.let { return it }
        releaseTarget()
        val created = TextureTarget("Afterimage preview", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT)
        target = created
        color = gfx.wrap(created.colorTexture!!)
        sky = SkyRenderer(minecraft.textureManager, minecraft.atlasManager, created)
        return created
    }

    private fun syncGraph(renderer: LevelRenderer, area: ViewArea, graph: SectionOcclusionGraph) {
        val main = renderer.sectionOcclusionGraph()
        if (main !== mainGraph) mainGraph = main
        if (viewArea !== area) {
            viewArea = area
            viewCenter = null
            graph.waitAndReset(area)
            visibleSections.clear()
            nearbyVisibleSections.clear()
            val source = main as SectionOcclusionGraphAccessor
            clearPending()
            addedLoaded.addAll(source.afterimage_loadedChunks())
            addedEmpty.addAll(source.afterimage_emptySections())
        }
        val center = area.cameraSectionPos
        if (center != viewCenter) {
            viewCenter = center
            graph.invalidate()
        }
    }

    private fun fillChunkLoading(graph: SectionOcclusionGraph, level: ClientLevel) {
        loading.addedLoadedChunks = addedLoaded
        loading.removedLoadedChunks = removedLoaded
        loading.addedEmptySections = addedEmpty
        loading.removedEmptySections = removedEmpty
        loading.loadedExpectedChunks.clear()
        val chunks = level.chunkSource
        val expected = graph.expectedChunks().longIterator()
        while (expected.hasNext()) {
            val chunk = expected.nextLong()
            if (chunks.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) loading.loadedExpectedChunks.add(chunk)
        }
    }

    private fun clearPending() {
        addedLoaded.clear()
        removedLoaded.clear()
        addedEmpty.clear()
        removedEmpty.clear()
    }

    private fun merge(added: LongOpenHashSet, removed: LongOpenHashSet, newlyAdded: LongOpenHashSet, newlyRemoved: LongOpenHashSet) {
        added.addAll(newlyAdded)
        removed.removeAll(newlyAdded)
        removed.addAll(newlyRemoved)
        added.removeAll(newlyRemoved)
    }

    private fun releaseIfIdle() {
        if (target != null && frame - requestFrame > IDLE_FRAMES) release()
    }

    private fun releaseTarget() {
        runCatching { sky?.close() }
        sky = null
        color?.close()
        color = null
        target?.destroyBuffers()
        target = null
        textureId = 0
    }

    fun release() {
        val preview = graph
        graph = null
        mainGraph = null
        runCatching { preview?.waitAndReset(null) }
        releaseTarget()
        runCatching { fog?.close() }
        runCatching { projectionBuffer?.close() }
        runCatching { globals?.close() }
        globals = null
        runCatching { clouds?.close() }
        fog = null
        projectionBuffer = null
        clouds = null
        state = null
        extractor = null
        previewCamera = null
        entity = null
        level = null
        viewArea = null
        viewCenter = null
        visibleSections.clear()
        nearbyVisibleSections.clear()
        clearPending()
        width = 0
        height = 0
    }

    private companion object {
        const val IDLE_FRAMES = 600
    }
}
