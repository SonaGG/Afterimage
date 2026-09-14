package gg.sona.recast.mc

import gg.sona.recast.camera.CameraPose
import gg.sona.recast.camera.PoseSource
import gg.sona.recast.camera.Rotation
import gg.sona.recast.clip.export.ExportHandle
import gg.sona.recast.clip.export.ExportJob
import gg.sona.recast.clip.export.ExportProgress
import gg.sona.recast.clip.export.ExportQueue
import gg.sona.recast.editor.look.LookSettings
import gg.sona.recast.mc.ui.WorkspaceHost
import gg.sona.recast.render.*
import gg.sona.recast.render.ffmpeg.AudioFileDecoder
import gg.sona.recast.render.ffmpeg.AudioMuxer
import gg.sona.recast.render.ffmpeg.FfmpegRuntime
import gg.sona.recast.render.sink.*
import gg.sona.recast.replay.session.ReplaySession
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.living.player.ClientPlayerEntity
import net.minecraft.client.render.pipeline.RenderTarget
import net.minecraft.client.render.platform.GlStateManager
import org.apache.logging.log4j.LogManager
import org.joml.Vector3d
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

class FramebufferExporter(
    private val minecraft: Minecraft,
    private val exports: ExportQueue,
    private val replay: () -> ReplaySession?,
    private val camera: CameraDriver,
    private val workspace: WorkspaceHost,
    private val ffmpeg: FfmpegRuntime,
    private val post: PostProcessor,
) : ExportBackend, FrameSource, MainThreadExecutor {

    private class FrameRequest(val action: () -> Any?, val latch: CountDownLatch) {
        @Volatile
        var result: Any? = null

        @Volatile
        var failure: Throwable? = null

        @Volatile
        var capturePass: Int = -1
    }

    private val logger = LogManager.getLogger("Recast")
    val sounds = SoundCapture(minecraft)
    private val pending = AtomicReference<FrameRequest?>(null)
    private var started: FrameRequest? = null
    private var readback: ByteBuffer? = null
    private var previousHideGui = false
    private var exporting = false
    private var settings: ExportSettings? = null
    private var renderWidth = 0
    private var renderHeight = 0
    private var exportTarget: RenderTarget? = null
    private var frameActive = false
    private var savedWidth = 0
    private var savedHeight = 0
    private var currentPipeline: ExportPipeline? = null
    private var exportStartedNanos = 0L
    private var passBuffers: Array<ByteArray> = emptyArray()
    private var lut: EquirectLut? = null

    private var accumulation: AccumulationBuffer? = null
    private var accumPending = false
    private var readbackStaging: ByteBuffer? = null
    private var postBuffer: AccumulationBuffer? = null
    private var depthBuffer: DepthMapBuffer? = null
    private var depthStaging: ByteBuffer? = null
    private val depthTexture = DepthTexture()
    private var depthWanted = false
    private var depthReady = false
    private val scratch = ScratchTexture()
    private var sampleIndex = -1
    private var frameFocus = 8.0
    private var currentIndex = FrameSource.NO_FRAME
    private var currentSlot = 0
    private var pendingIndex = FrameSource.NO_FRAME
    private var pendingSlot = 0
    private var pendingPost = false
    private var lookRequested = false
    var focusDistance: (Long, CameraPose) -> Double = { _, _ -> 8.0 }
    var projectionScale: () -> FloatArray = { floatArrayOf(1f, 1f) }

    val transparentExport: Boolean get() = exporting && settings?.transparent == true
    private var incompleteChunkFrames = 0
    private val missingSkinPlayers = HashSet<Int>()
    private var equirectAccumulator: IntArray? = null
    private var equirectScratch: ByteArray? = null

    override val windowWidth: Int get() = if (frameActive) savedWidth else minecraft.width

    override val windowHeight: Int get() = if (frameActive) savedHeight else minecraft.height

    override val ffmpegPath: String get() = ffmpeg.directory.toString()

    override val ffmpegAvailable: Boolean get() = ffmpeg.available

    override val ffmpegDownloadSupported: Boolean get() = ffmpeg.downloadSupported

    override val ffmpegVersion: String? get() = ffmpeg.version

    override fun encoders(): Set<String> = ffmpeg.encoders()

    override fun downloadFfmpeg(): ExportHandle = exports.submit(ffmpeg.downloadJob())

    override fun queue(): ExportQueue = exports

    private val reservedOutputs = Collections.synchronizedSet(HashSet<Path>())

    private fun uniqueOutput(requested: Path): Path {
        val name = requested.fileName.toString()
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var candidate = requested
        var index = 2
        fun taken(path: Path): Boolean {
            val sequenceFolder = path.resolveSibling(path.fileName.toString().substringBeforeLast('.'))
            return reservedOutputs.contains(path.toAbsolutePath()) || Files.exists(path) ||
                    Files.exists(LibavVideoSink.partFile(path)) || Files.isDirectory(sequenceFolder)
        }
        while (taken(candidate) && index < 10_000) {
            candidate = requested.resolveSibling("$stem ($index)$extension")
            index++
        }
        reservedOutputs.add(candidate.toAbsolutePath())
        return candidate
    }

    override fun submit(request: ExportRequest): ExportHandle? {
        val session = replay() ?: return null
        val output = uniqueOutput(request.settings.output)
        val settings = request.settings.copy(
            width = request.settings.width and 1.inv(),
            height = request.settings.height and 1.inv(),
            output = output
        )
        val folder = settings.output.resolveSibling(settings.output.fileName.toString().substringBeforeLast('.'))
        val sink: FrameSink = when {
            settings.format == ExportFormat.PNG_SEQUENCE || request.target == ExportTarget.PNG_SEQUENCE && !settings.format.needsFfmpeg -> PngSequenceSink(
                folder
            )

            settings.format == ExportFormat.JPEG_SEQUENCE -> JpegSequenceSink(folder, settings.jpegQuality)
            request.target == ExportTarget.PNG_SEQUENCE -> PngSequenceSink(folder)
            else -> LibavVideoSink(ffmpeg.encoders())
        }
        val poseSource = PoseSource { nanos -> camera.exportPoseAt(nanos) }
        val pipeline = ExportPipeline(settings, session.exportDriver(), poseSource, this, AsyncFrameSink(sink), this)
        val render = RenderJob(pipeline, "export ${request.title}")
        currentPipeline = pipeline
        return exports.submit(object : ExportJob {
            override val name: String get() = render.name

            override fun run(progress: (Double) -> Unit): Path = run(ExportProgress(progress) {})

            override fun run(report: ExportProgress): Path {
                try {
                    val rendered = render.run(report)
                    if (!pipeline.cancelled) finishAudio(settings, rendered, report)
                    return rendered
                } finally {
                    reservedOutputs.remove(output.toAbsolutePath())
                }
            }

            override fun cancel() = render.cancel()
        })
    }

    private fun finishAudio(settings: ExportSettings, output: Path, report: ExportProgress) {
        val events = if (settings.gameAudio) sounds.drain() else emptyList()
        val file = settings.audioFile?.takeIf { settings.format.supportsAudio }
        if (events.isEmpty() && file == null) return
        val base = output.fileName.toString().substringBeforeLast('.')
        val wav = output.resolveSibling("$base.wav")
        try {
            if (events.isNotEmpty()) report.detail("mixing ${events.size} game sounds")
            val mix = GameAudioMixer.mix(
                events,
                settings.outputDurationNanos + settings.frameIntervalNanos,
                { sounds.clip(it) },
                settings.gameAudioVolume.toFloat()
            )
            if (events.isNotEmpty()) GameAudioMixer.writeWav(mix, wav)
            if (!settings.format.needsFfmpeg || !settings.format.supportsAudio || !ffmpeg.available) return
            if (file != null) {
                report.detail("decoding ${file.fileName}")
                AudioFileDecoder.mixInto(mix, file, settings.audioOffsetSeconds, settings.audioVolume)
            }
            ExportEncoding.applyAudioFades(mix, settings)
            report.detail("muxing audio")
            val muxed = output.resolveSibling("$base.audio-tmp.${settings.format.extension}")
            try {
                AudioMuxer(mix, settings.format).mux(output, muxed, ExportEncoding.containerOptions(settings))
                Files.move(muxed, output, StandardCopyOption.REPLACE_EXISTING)
                Files.deleteIfExists(wav)
            } catch (error: Throwable) {
                Files.deleteIfExists(muxed)
                logger.warn("Recast could not mux audio into {}", output, error)
                report.warn("Audio could not be muxed into the video: ${error.message}")
            }
        } catch (error: Throwable) {
            logger.warn("Recast could not render audio for {}", output, error)
            report.warn("Audio could not be rendered: ${error.message}")
        } finally {
            sounds.clips.clear()
        }
    }


    override fun prepare(settings: ExportSettings) {
        this.settings = settings
        exporting = true
        camera.exporting = true
        chunks.calibrate()
        skinGiveUp.clear()
        missingSkinPlayers.clear()
        incompleteChunkFrames = 0
        skinWaitFrames = 0
        exportStartedNanos = System.nanoTime()
        sounds.reset()
        sounds.muted = true
        sounds.capturing = false
        sounds.listener = { camera.currentPose() }
        chunkWaitFrames = 0
        previousHideGui = minecraft.options.hideGui
        minecraft.options.hideGui = true
        workspace.close()
        onExportStarted()
        val supersample = settings.supersampleFactor
        val (passWidth, passHeight) = passSize(settings)
        renderWidth = passWidth * supersample
        renderHeight = passHeight * supersample
        exportTarget?.destroyBuffers()
        exportTarget = RenderTarget(renderWidth, renderHeight, true).also {
            it.setClearColor(0f, 0f, 0f, if (settings.transparent) 0f else 1f)
            it.setFilterMode(GL11.GL_LINEAR)
        }
        val equirect = settings.projection == ExportProjection.EQUIRECTANGULAR
        val depthCapable =
            settings.projection == ExportProjection.PERSPECTIVE || settings.projection == ExportProjection.ORTHOGRAPHIC
        accumulation?.destroy()
        accumulation = null
        postBuffer?.destroy()
        postBuffer = null
        depthBuffer?.destroy()
        depthBuffer = null
        depthStaging = null
        depthTexture.destroy()
        scratch.destroy()
        depthWanted = depthCapable && (settings.depthMap || settings.depthOfField)
        lookRequested = settings.lookActive
        depthReady = false
        sampleIndex = -1
        accumPending = false
        currentIndex = FrameSource.NO_FRAME
        pendingIndex = FrameSource.NO_FRAME
        pendingPost = false
        if (equirect) {
            readbackStaging = null
            passBuffers = Array(settings.projection.passes) { ByteArray(renderWidth * renderHeight * 4) }
            readback = BufferUtils.createByteBuffer(renderWidth * renderHeight * 4)
        } else {
            GlStateManager.bindTexture(exportTarget!!.colorTextureId)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR)
            GlStateManager.bindTexture(0)
            accumulation = AccumulationBuffer(settings.width, settings.height)
            readbackStaging = BufferUtils.createByteBuffer(settings.width * settings.height * 4)
            if (settings.lookActive && post.available) postBuffer = AccumulationBuffer(settings.width, settings.height, GL11.GL_RGBA8)
            if (settings.depthMap && depthCapable) {
                depthBuffer = DepthMapBuffer(settings.width, settings.height)
                depthStaging = BufferUtils.createByteBuffer(settings.width * settings.height * 2)
            }
            passBuffers = emptyArray()
            readback = null
        }
        equirectAccumulator = if (equirect && settings.motionBlur.samples.coerceIn(1, 64) > 1)
            IntArray(settings.width * settings.height * 4) else null
        equirectScratch = null
        lut = if (equirect) EquirectLut(
            settings.width,
            settings.height,
            renderWidth,
            EQUIRECT_FOV
        ) else null
    }

    private fun passSize(settings: ExportSettings): Pair<Int, Int> = when (settings.projection) {
        ExportProjection.PERSPECTIVE, ExportProjection.ORTHOGRAPHIC -> settings.width to settings.height
        ExportProjection.STEREO_SBS -> (settings.width / 2) to settings.height
        ExportProjection.CUBE_MAP -> (settings.width / 3) to (settings.width / 3)
        ExportProjection.EQUIRECTANGULAR -> settings.height to settings.height
    }

    fun beginRender() {
        val target = exportTarget ?: return
        if (!exporting || frameActive) return
        savedWidth = minecraft.width
        savedHeight = minecraft.height
        minecraft.width = renderWidth
        minecraft.height = renderHeight
        frameActive = true
        target.bindWrite(true)
    }

    fun endRender() {
        if (!frameActive) return
        exportTarget?.let { RenderTargets.makeOpaque(it) }
        frameActive = false
        minecraft.width = savedWidth
        minecraft.height = savedHeight
        val window = minecraft.renderTarget
        window.bindWrite(true)
        GlStateManager.clearColor(0.05f, 0.05f, 0.06f, 1f)
        GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT)
    }

    fun activeTarget(): RenderTarget? = if (frameActive) exportTarget else null

    override fun live(): ExportLive? {
        val settings = this.settings ?: return null
        if (!exporting) return null
        val pipeline = currentPipeline
        val target = exportTarget
        val processed = postBuffer?.takeIf { pendingPost }
        return ExportLive(
            settings,
            pipeline?.framesDone ?: 0L,
            pipeline?.frameCount ?: settings.frameCount,
            exportStartedNanos,
            processed?.texture ?: target?.colorTextureId ?: 0,
            processed?.width ?: target?.width ?: 0,
            processed?.height ?: target?.height ?: 0,
            chunkWaitStatus,
        )
    }

    override fun beginFrame(index: Long, outputNanos: Long) {
        sounds.outputNanos = outputNanos
        if (index == 0L) sounds.capturing = false
        equirectAccumulator?.let { Arrays.fill(it, 0) }
        accumPending = accumulation != null
        currentIndex = index
        sampleIndex = -1
        depthReady = false
    }

    override fun renderPass(nanos: Long, pose: CameraPose, pass: Int) {
        val settings = this.settings ?: return
        camera.exportPose = passPose(settings, pose, pass)
        if (pass == 0) {
            sampleIndex++
            frameFocus = focusDistance(nanos, pose)
        }

        sounds.capturing = settings.gameAudio
        camera.exportFov = faceFov(settings)
        camera.exportOrthoScale =
            if (settings.projection == ExportProjection.ORTHOGRAPHIC) settings.orthoScale else null
        started?.capturePass = pass
    }

    private fun faceFov(settings: ExportSettings): Float = when (settings.projection) {
        ExportProjection.CUBE_MAP -> FACE_FOV
        ExportProjection.EQUIRECTANGULAR -> EQUIRECT_FOV
        else -> 0f
    }

    private fun passPose(settings: ExportSettings, base: CameraPose, pass: Int): CameraPose? =
        when (settings.projection) {
            ExportProjection.PERSPECTIVE, ExportProjection.ORTHOGRAPHIC -> null
            ExportProjection.STEREO_SBS -> {
                val offset =
                    Vector3d(base.rotation.right()).mul(settings.stereoSeparation / 2.0 * (if (pass == 0) -1.0 else 1.0))
                CameraPose(Vector3d(base.position).add(offset), base.rotation, base.fov)
            }

            ExportProjection.CUBE_MAP, ExportProjection.EQUIRECTANGULAR -> {
                val yaw = base.rotation.yaw
                val rotation = when (pass) {
                    0 -> Rotation(yaw, 0.0)
                    1 -> Rotation(yaw + 90.0, 0.0)
                    2 -> Rotation(yaw + 180.0, 0.0)
                    3 -> Rotation(yaw + 270.0, 0.0)
                    4 -> Rotation(yaw, -90.0)
                    else -> Rotation(yaw, 90.0)
                }
                CameraPose(Vector3d(base.position), rotation, faceFov(settings).toDouble())
            }
        }

    override fun compose(into: ByteArray, depthInto: FloatArray?, sample: Int, samples: Int): Long {
        val settings = this.settings ?: return FrameSource.NO_FRAME
        if (settings.projection == ExportProjection.EQUIRECTANGULAR) {
            composeEquirect(into, sample, samples)
            if (sample != samples - 1) return FrameSource.NO_FRAME
            if (depthInto != null) Arrays.fill(depthInto, 0f)
            return currentIndex
        }
        if (sample != samples - 1) return FrameSource.NO_FRAME
        val delivered = collectPending(into, depthInto, settings)
        val accumulation = accumulation ?: return delivered
        val processed = postBuffer?.let { runGrade(accumulation, it, settings) } == true
        if (processed) postBuffer!!.issueReadback(currentSlot) else accumulation.issueReadback(currentSlot)
        depthBuffer?.let {
            if (!depthReady) clearDepth(it)
            it.issueReadback(currentSlot)
        }
        pendingIndex = currentIndex
        pendingSlot = currentSlot
        pendingPost = processed
        currentSlot = (currentSlot + 1) % AccumulationBuffer.READBACK_SLOTS
        return delivered
    }

    private fun lookFrame(look: LookSettings, settings: ExportSettings): PostProcessor.Frame {
        val samples = settings.motionBlur.sampleCount
        return PostProcessor.Frame(
            look,
            frameFocus,
            PostProcessor.NEAR_PLANE,
            PostProcessor.farPlane(minecraft.options.viewDistance),
            settings.projection == ExportProjection.ORTHOGRAPHIC,
            projectionScale(),
            (currentIndex % 4096L).toFloat(),
            maxOf(MIN_SAMPLE_TAPS, PostProcessor.EXPORT_TAPS / samples),
            PostProcessor.EXPORT_SPACING * settings.supersampleFactor,
            sampleIndex * SAMPLE_TAP_ROTATION,
        )
    }

    private fun runGrade(accumulation: AccumulationBuffer, target: AccumulationBuffer, settings: ExportSettings): Boolean {
        val look = settings.look ?: return false
        GL11.glPushAttrib(GL11.GL_VIEWPORT_BIT)
        try {
            target.bind()
            GL11.glViewport(0, 0, target.width, target.height)
            return post.draw(PostProcessor.Stage.GRADE, accumulation.texture, 0, target.width, target.height, lookFrame(look, settings))
        } finally {
            GL11.glPopAttrib()
        }
    }

    private fun runDepthOfField(target: RenderTarget, settings: ExportSettings) {
        val look = settings.look ?: return
        scratch.copyFromReadFramebuffer(0, 0, renderWidth, renderHeight)
        GL11.glPushAttrib(GL11.GL_VIEWPORT_BIT)
        try {
            GL11.glViewport(0, 0, renderWidth, renderHeight)
            post.draw(PostProcessor.Stage.DEPTH_OF_FIELD, scratch.id, depthTexture.id, renderWidth, renderHeight, lookFrame(look, settings))
        } finally {
            GL11.glPopAttrib()
        }
    }

    private fun runDepthMap(target: DepthMapBuffer, settings: ExportSettings): Boolean {
        GL11.glPushAttrib(GL11.GL_VIEWPORT_BIT)
        try {
            target.bind()
            GL11.glViewport(0, 0, target.width, target.height)
            return post.drawDepth(
                depthTexture.id,
                renderWidth,
                renderHeight,
                settings.supersampleFactor,
                PostProcessor.NEAR_PLANE,
                PostProcessor.farPlane(minecraft.options.viewDistance),
                settings.projection == ExportProjection.ORTHOGRAPHIC,
                settings.depthRange.toFloat(),
            )
        } finally {
            GL11.glPopAttrib()
        }
    }

    private fun clearDepth(target: DepthMapBuffer) {
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_SCISSOR_BIT)
        try {
            target.bind()
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            GL11.glColorMask(true, true, true, true)
            GL11.glClearColor(0f, 0f, 0f, 1f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        } finally {
            GL11.glPopAttrib()
        }
    }

    override fun flush(into: ByteArray, depthInto: FloatArray?): Long {
        val settings = this.settings ?: return FrameSource.NO_FRAME
        return collectPending(into, depthInto, settings)
    }

    private fun collectPending(into: ByteArray, depthInto: FloatArray?, settings: ExportSettings): Long {
        val index = pendingIndex
        if (index == FrameSource.NO_FRAME) return FrameSource.NO_FRAME
        val source = (if (pendingPost) postBuffer else accumulation) ?: return FrameSource.NO_FRAME
        val staging = readbackStaging ?: return FrameSource.NO_FRAME
        source.collectReadback(pendingSlot, staging, into)
        if (!settings.transparent) forceOpaque(into, settings)
        if (depthInto != null) {
            val depth = depthBuffer
            val depthStaging = depthStaging
            if (depth != null && depthStaging != null) depth.collectReadback(pendingSlot, depthStaging, depthInto)
            else Arrays.fill(depthInto, 0f)
        }
        pendingIndex = FrameSource.NO_FRAME
        return index
    }

    override fun warnings(): List<String> {
        val result = ArrayList<String>(4)
        if (incompleteChunkFrames > 0) result += "$incompleteChunkFrames frames were captured while chunks were still compiling"
        if (missingSkinPlayers.isNotEmpty()) result += "${missingSkinPlayers.size} players were rendered without their skin"
        if (lookRequested) {
            post.failure?.let { result += "The look was not applied: $it" }
            post.lutWarning?.let { result += it }
        }
        return result
    }

    private fun composeEquirect(into: ByteArray, sample: Int, samples: Int) {
        val lut = this.lut ?: return
        val accumulator = equirectAccumulator
        if (accumulator == null) {
            lut.compose(passBuffers, into)
            return
        }
        val scratch = equirectScratch ?: ByteArray(into.size).also { equirectScratch = it }
        lut.compose(passBuffers, scratch)
        for (i in accumulator.indices) accumulator[i] += scratch[i].toInt() and 0xFF
        if (sample == samples - 1) for (i in into.indices) into[i] = (accumulator[i] / samples).toByte()
    }

    private fun passViewport(settings: ExportSettings, pass: Int): IntArray = when (settings.projection) {
        ExportProjection.PERSPECTIVE, ExportProjection.ORTHOGRAPHIC -> intArrayOf(0, 0, settings.width, settings.height)
        ExportProjection.STEREO_SBS -> {
            val half = settings.width / 2
            if (pass == 0) intArrayOf(0, 0, half, settings.height) else intArrayOf(half, 0, half, settings.height)
        }

        ExportProjection.CUBE_MAP -> {
            val face = settings.width / 3
            val topY = (pass / 3) * face
            intArrayOf((pass % 3) * face, settings.height - topY - face, face, face)
        }

        ExportProjection.EQUIRECTANGULAR -> intArrayOf(0, 0, settings.width, settings.height)
    }

    private fun captureGpu(pass: Int, settings: ExportSettings) {
        val target = exportTarget ?: return
        val accum = accumulation ?: return
        val samples = settings.motionBlur.sampleCount
        val viewport = passViewport(settings, pass)

        if (accumPending) {
            accumPending = false
            accum.clear()
        }
        accum.bind()
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        try {
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.colorTextureId)
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D)
            GL11.glEnable(GL11.GL_TEXTURE_2D)
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDepthMask(false)
            GL11.glDisable(GL11.GL_ALPHA_TEST)
            GL11.glDisable(GL11.GL_CULL_FACE)
            GL11.glDisable(GL11.GL_LIGHTING)
            GL11.glDisable(GL11.GL_FOG)
            if (samples > 1) {
                GL11.glEnable(GL11.GL_BLEND)
                GL14.glBlendColor(0f, 0f, 0f, 1f / samples)
                GL11.glBlendFunc(GL14.GL_CONSTANT_ALPHA, GL11.GL_ONE)
            } else {
                GL11.glDisable(GL11.GL_BLEND)
            }
            GL11.glColor4f(1f, 1f, 1f, 1f)
            GL11.glBegin(GL11.GL_QUADS)
            GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(-1f, -1f)
            GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(1f, -1f)
            GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(1f, 1f)
            GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(-1f, 1f)
            GL11.glEnd()
        } finally {
            GL11.glMatrixMode(GL11.GL_PROJECTION)
            GL11.glPopMatrix()
            GL11.glMatrixMode(GL11.GL_MODELVIEW)
            GL11.glPopMatrix()
            GL11.glPopAttrib()
        }
    }

    private fun forceOpaque(into: ByteArray, settings: ExportSettings) {
        val rows = if (settings.projection == ExportProjection.CUBE_MAP)
            minOf(2 * (settings.width / 3), settings.height) else settings.height
        var index = 3
        val end = settings.width * rows * 4
        while (index < end) {
            into[index] = 0xFF.toByte()
            index += 4
        }
    }

    var onExportStarted: () -> Unit = {}
    var onExportFinished: () -> Unit = {}

    override fun release() {
        sounds.muted = false
        sounds.capturing = false
        exporting = false
        camera.exporting = false
        camera.exportPose = null
        camera.exportFov = 0f
        minecraft.options.hideGui = previousHideGui
        endRender()
        exportTarget?.destroyBuffers()
        exportTarget = null
        accumulation?.destroy()
        accumulation = null
        postBuffer?.destroy()
        postBuffer = null
        depthBuffer?.destroy()
        depthBuffer = null
        depthStaging = null
        depthTexture.destroy()
        scratch.destroy()
        depthWanted = false
        depthReady = false
        accumPending = false
        readbackStaging = null
        currentIndex = FrameSource.NO_FRAME
        pendingIndex = FrameSource.NO_FRAME
        pendingPost = false
        readback = null
        passBuffers = emptyArray()
        equirectAccumulator = null
        equirectScratch = null
        lut = null
        settings = null
        onExportFinished()
    }

    override fun <T> call(action: () -> T): T {
        val request = FrameRequest(action, CountDownLatch(1))
        while (!pending.compareAndSet(null, request)) Thread.onSpinWait()
        if (!request.latch.await(30, TimeUnit.SECONDS)) {
            pending.compareAndSet(request, null)
            throw IllegalStateException("render thread did not service the export frame in time")
        }
        request.failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return request.result as T
    }

    fun onFrameStart() {
        if (started != null) return
        val request = pending.get() ?: return
        started = request
        val framebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
        try {
            request.result = request.action()
        } catch (error: Throwable) {
            request.failure = error
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
        }
        if (request.capturePass < 0) finish(request)
    }

    private val chunks = ChunkReadiness(minecraft)
    private var chunkWaitFrames = 0
    private var skinWaitFrames = 0
    var chunkWaitStatus: String = ""
        private set

    fun onFrameEnd() {
        try {
            captureFrame()
        } finally {
            endRender()
        }
    }

    private fun captureFrame() {
        val request = started ?: return
        val pass = request.capturePass
        if (pass >= 0) {
            if (settings?.waitForChunks == true && !chunks.settled()) {
                if (chunkWaitFrames < MAX_CHUNK_WAIT_FRAMES) {
                    chunkWaitFrames++
                    chunkWaitStatus = "waiting for chunks: " + chunks.pendingDescription()
                    return
                }
                incompleteChunkFrames++
            }
            if (settings?.waitForChunks == true && !skinsReady()) {
                chunkWaitStatus = "waiting for player skins"
                return
            }
            chunkWaitFrames = 0
            chunkWaitStatus = ""
            try {
                capture(pass)
            } catch (error: Throwable) {
                request.failure = error
            }
        }
        finish(request)
    }

    private val skinGiveUp = HashSet<Int>()

    private fun missingSkins(): List<Int> {
        val world = minecraft.world ?: return emptyList()
        val camera = minecraft.camera
        val missing = ArrayList<Int>()
        for (entity in world.entities) {
            if (entity !is ClientPlayerEntity || entity === minecraft.player || entity.hasSkinTexture()) continue
            if (entity.networkId in skinGiveUp) continue
            if (camera != null) {
                val dx = entity.x - camera.x
                val dy = entity.y - camera.y
                val dz = entity.z - camera.z
                if (dx * dx + dy * dy + dz * dz > SKIN_WAIT_RANGE * SKIN_WAIT_RANGE) continue
            }
            missing += entity.networkId
        }
        return missing
    }

    private fun skinsReady(): Boolean {
        val missing = missingSkins()
        if (missing.isEmpty()) {
            skinWaitFrames = 0
            return true
        }
        if (skinWaitFrames >= MAX_SKIN_WAIT_FRAMES) {
            skinGiveUp.addAll(missing)
            missingSkinPlayers.addAll(missing)
            skinWaitFrames = 0
            return true
        }
        skinWaitFrames++
        return false
    }

    fun chunkDeadline(finishNanos: Long): Long = if (exporting) System.nanoTime() + CHUNK_BUDGET_NANOS else finishNanos


    fun onTimerAdvanced(): Boolean {
        if (!exporting) return false
        val fresh = started == null
        if (fresh && pending.get() == null) awaitRequest()
        onFrameStart()
        if (!exporting) return false
        val request = started
        return request == null || request.capturePass < 0 || !fresh
    }

    private fun awaitRequest() {
        val deadline = System.nanoTime() + REQUEST_WAIT_NANOS
        while (pending.get() == null && System.nanoTime() < deadline) LockSupport.parkNanos(50_000L)
    }

    val isExporting: Boolean get() = exporting

    val synchronousChunks: Boolean get() = settings?.waitForChunks == true

    private fun finish(request: FrameRequest) {
        started = null
        pending.compareAndSet(request, null)
        request.latch.countDown()
    }

    fun onWorldPassEnd() {
        if (!exporting || !frameActive || !depthWanted) return
        val request = started ?: return
        if (request.capturePass != 0) return
        val settings = this.settings ?: return
        val middle = sampleIndex == settings.motionBlur.sampleCount / 2
        val depthOfField = settings.depthOfField
        if (!middle && !depthOfField) return
        val target = exportTarget ?: return
        target.bindWrite(false)
        if (!depthTexture.copyFromReadFramebuffer(0, 0, renderWidth, renderHeight)) return
        val depthMap = depthBuffer
        if (middle && depthMap != null) {
            depthReady = runDepthMap(depthMap, settings)
            target.bindWrite(true)
        }
        if (depthOfField) runDepthOfField(target, settings)
    }

    private fun capture(pass: Int) {
        val settings = this.settings ?: return
        if (settings.projection != ExportProjection.EQUIRECTANGULAR) {
            captureGpu(pass, settings)
            return
        }
        val width = renderWidth
        val height = renderHeight
        val target = passBuffers.getOrNull(pass) ?: return
        val buffer = readback ?: return
        exportTarget?.bindWrite(false)
        buffer.clear()
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
        val rowBytes = width * 4
        for (row in 0 until height) {
            buffer.position((height - 1 - row) * rowBytes)
            buffer.get(target, row * rowBytes, rowBytes)
        }
        for (index in 3 until target.size step 4) target[index] = 0xFF.toByte()
    }


    private companion object {
        const val FACE_FOV = 90f
        const val MAX_CHUNK_WAIT_FRAMES = 240
        const val MAX_SKIN_WAIT_FRAMES = 90
        const val SKIN_WAIT_RANGE = 96.0
        const val CHUNK_BUDGET_NANOS = 250_000_000L
        const val REQUEST_WAIT_NANOS = 8_000_000L
        const val EQUIRECT_FOV = 92f
        const val MIN_SAMPLE_TAPS = 512
        const val SAMPLE_TAP_ROTATION = 0.7853982f
    }
}
