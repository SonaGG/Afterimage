package gg.sona.recast.mc

import gg.sona.recast.camera.CameraPose
import gg.sona.recast.camera.PoseSource
import gg.sona.recast.camera.Rotation
import gg.sona.recast.clip.export.ExportHandle
import gg.sona.recast.clip.export.ExportJob
import gg.sona.recast.clip.export.ExportProgress
import gg.sona.recast.clip.export.ExportQueue
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
import java.nio.FloatBuffer
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
    private var depthReadback: FloatBuffer? = null
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
    private var passDepth: FloatArray? = null
    private var lut: EquirectLut? = null

    private var accumTarget: RenderTarget? = null
    private var accumPending = false
    private var finalReadback: ByteBuffer? = null
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
            return reservedOutputs.contains(path.toAbsolutePath()) || Files.exists(path) || Files.isDirectory(
                sequenceFolder
            )
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
        val pipeline = ExportPipeline(settings, session, poseSource, this, AsyncFrameSink(sink), this)
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
            if (events.isNotEmpty()) report.detail("mixing game audio · ${events.size} sounds")
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
            }
        } catch (error: Throwable) {
            logger.warn("Recast could not render audio for {}", output, error)
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
        val supersample = settings.supersample.coerceIn(1, 4)
        val (passWidth, passHeight) = passSize(settings)
        renderWidth = passWidth * supersample
        renderHeight = passHeight * supersample
        exportTarget?.destroyBuffers()
        exportTarget = RenderTarget(renderWidth, renderHeight, true).also {
            it.setClearColor(0f, 0f, 0f, 1f)
            it.setFilterMode(GL11.GL_LINEAR)
        }
        val equirect = settings.projection == ExportProjection.EQUIRECTANGULAR
        accumTarget?.destroyBuffers()
        if (equirect) {
            accumTarget = null
            finalReadback = null
            passBuffers = Array(settings.projection.passes) { ByteArray(renderWidth * renderHeight * 4) }
            readback = BufferUtils.createByteBuffer(renderWidth * renderHeight * 4)
        } else {
            GlStateManager.bindTexture(exportTarget!!.colorTextureId)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR)
            GlStateManager.bindTexture(0)
            accumTarget = RenderTarget(settings.width, settings.height, false)
            finalReadback = BufferUtils.createByteBuffer(settings.width * settings.height * 4)
            passBuffers = emptyArray()
            readback = null
        }
        passDepth = if (settings.depthMap) FloatArray(renderWidth * renderHeight) else null
        depthReadback = if (settings.depthMap) BufferUtils.createFloatBuffer(renderWidth * renderHeight) else null
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
        return ExportLive(
            settings,
            pipeline?.framesDone ?: 0L,
            pipeline?.frameCount ?: settings.frameCount,
            exportStartedNanos,
            target?.colorTextureId ?: 0,
            target?.width ?: 0,
            target?.height ?: 0,
            chunkWaitStatus,
        )
    }

    override fun beginFrame(index: Long, outputNanos: Long) {
        sounds.outputNanos = outputNanos
        if (index == 0L) sounds.capturing = false
        equirectAccumulator?.let { Arrays.fill(it, 0) }
        accumPending = accumTarget != null
    }

    override fun renderPass(nanos: Long, pose: CameraPose, pass: Int) {
        val settings = this.settings ?: return
        camera.exportPose = passPose(settings, pose, pass)

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

    override fun compose(into: ByteArray, depthInto: FloatArray?, sample: Int, samples: Int) {
        val settings = this.settings ?: return
        val supersample = settings.supersample.coerceIn(1, 4)
        if (settings.projection == ExportProjection.EQUIRECTANGULAR) {
            composeEquirect(into, sample, samples)
        } else if (sample == samples - 1) {
            readAccumulation(into, settings)
            forceOpaque(into, settings)
        }
        if (depthInto != null) {
            val depth = passDepth
            if (depth != null && (settings.projection == ExportProjection.PERSPECTIVE || settings.projection == ExportProjection.ORTHOGRAPHIC)) downsampleDepth(
                depth,
                renderWidth,
                supersample,
                depthInto,
                settings.width,
                settings.height
            )
            else Arrays.fill(depthInto, 0f)
        }
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
        val accum = accumTarget ?: return
        val samples = settings.motionBlur.samples.coerceIn(1, 64)
        val viewport = passViewport(settings, pass)

        accum.bindWrite(false)
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        try {
            if (accumPending) {
                accumPending = false
                GL11.glDisable(GL11.GL_SCISSOR_TEST)
                GL11.glColorMask(true, true, true, true)
                GL11.glClearColor(0f, 0f, 0f, 0f)
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            }
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

    private fun readAccumulation(into: ByteArray, settings: ExportSettings) {
        val accum = accumTarget ?: return
        val buffer = finalReadback ?: return
        val width = settings.width
        val height = settings.height
        accum.bindWrite(false)
        buffer.clear()
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
        val rowBytes = width * 4
        for (row in 0 until height) {
            buffer.position((height - 1 - row) * rowBytes)
            buffer.get(into, row * rowBytes, rowBytes)
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

    private fun downsampleDepth(
        source: FloatArray,
        sourceWidth: Int,
        factor: Int,
        target: FloatArray,
        targetWidth: Int,
        targetHeight: Int
    ) {
        val samples = factor * factor
        for (y in 0 until targetHeight) {
            for (x in 0 until targetWidth) {
                var sum = 0f
                for (dy in 0 until factor) {
                    val row = (y * factor + dy) * sourceWidth + x * factor
                    for (dx in 0 until factor) sum += source[row + dx]
                }
                target[y * targetWidth + x] = sum / samples
            }
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
        accumTarget?.destroyBuffers()
        accumTarget = null
        accumPending = false
        finalReadback = null
        readback = null
        depthReadback = null
        passBuffers = emptyArray()
        passDepth = null
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
        try {
            request.result = request.action()
        } catch (error: Throwable) {
            request.failure = error
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
            if (settings?.waitForChunks == true && !chunks.settled() && chunkWaitFrames < MAX_CHUNK_WAIT_FRAMES) {
                chunkWaitFrames++
                chunkWaitStatus = "waiting for chunks: " + chunks.pendingDescription()
                return
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

    fun captureDepth() {
        if (!exporting || !frameActive) return
        val request = started ?: return
        if (request.capturePass != 0) return
        val depth = passDepth ?: return
        val depthBuffer = depthReadback ?: return
        val width = renderWidth
        val height = renderHeight
        exportTarget?.bindWrite(false)
        depthBuffer.clear()
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
        GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depthBuffer)
        val near = NEAR_PLANE
        val far = minecraft.options.viewDistance * 16f * SQRT_2
        for (row in 0 until height) {
            val sourceRow = (height - 1 - row) * width
            val targetRow = row * width
            for (x in 0 until width) {
                val z = depthBuffer.get(sourceRow + x)
                val ndc = z * 2f - 1f
                val linear = 2f * near * far / (far + near - ndc * (far - near))
                depth[targetRow + x] = (linear / far).coerceIn(0f, 1f)
            }
        }
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
        const val NEAR_PLANE = 0.05f
        const val SQRT_2 = 1.4142135f
    }
}
