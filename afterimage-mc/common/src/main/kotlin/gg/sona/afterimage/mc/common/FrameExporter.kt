package gg.sona.afterimage.mc.common

import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.camera.PoseSource
import gg.sona.afterimage.camera.Rotation
import gg.sona.afterimage.clip.export.ExportHandle
import gg.sona.afterimage.clip.export.ExportJob
import gg.sona.afterimage.clip.export.ExportProgress
import gg.sona.afterimage.clip.export.ExportQueue
import gg.sona.afterimage.editor.look.LookSettings
import gg.sona.afterimage.gfx.Blend
import gg.sona.afterimage.gfx.Filter
import gg.sona.afterimage.gfx.Readback
import gg.sona.afterimage.gfx.SizedTexture
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.gfx.TextureFormat
import gg.sona.afterimage.gfx.Viewport
import gg.sona.afterimage.mc.common.ui.WorkspaceHost
import gg.sona.afterimage.render.*
import gg.sona.afterimage.render.look.PostProcessor
import gg.sona.afterimage.render.ffmpeg.AudioFileDecoder
import gg.sona.afterimage.render.ffmpeg.AudioMuxer
import gg.sona.afterimage.render.ffmpeg.FfmpegRuntime
import gg.sona.afterimage.render.sink.*
import gg.sona.afterimage.replay.session.ReplaySession
import gg.sona.afterimage.core.log.AfterimageLog
import org.joml.Vector3d
import org.lwjgl.BufferUtils
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

class FrameExporter(
    private val platform: ExportPlatform,
    private val exports: ExportQueue,
    private val replay: () -> ReplaySession?,
    private val camera: ExportCamera,
    private val workspace: WorkspaceHost,
    private val ffmpeg: FfmpegRuntime,
    private val post: PostProcessor,
    val sounds: SoundCaptureBase,
) : ExportBackend, FrameSource, MainThreadExecutor {

    private class FrameRequest(val action: () -> Any?, val latch: CountDownLatch) {
        @Volatile
        var result: Any? = null

        @Volatile
        var failure: Throwable? = null

        @Volatile
        var capturePass: Int = -1
    }

    private val logger = AfterimageLog.logger("Afterimage")
    private val pending = AtomicReference<FrameRequest?>(null)
    private var started: FrameRequest? = null
    private var readback: ByteBuffer? = null
    private var previousHideGui = false
    private var exporting = false
    private var settings: ExportSettings? = null
    private var renderWidth = 0
    private var renderHeight = 0
    private var exportTarget: ExportSurface? = null
    private var frameActive = false
    private var savedWidth = 0
    private var savedHeight = 0
    private var currentPipeline: ExportPipeline? = null
    private var exportStartedNanos = 0L
    private var passBuffers: Array<ByteArray> = emptyArray()
    private var lut: EquirectLut? = null

    private val gfx = platform.gfx
    private var accumulation: Target? = null
    private var accumulationReadback: Readback? = null
    private var accumPending = false
    private var postBuffer: Target? = null
    private var postReadback: Readback? = null
    private var depthBuffer: Target? = null
    private var depthReadback: Readback? = null
    private val depthTexture = SizedTexture(gfx, TextureFormat.DEPTH24)
    private var depthCopyFailed = false
    private var depthWanted = false
    private var depthReady = false
    private val scratch = SizedTexture(gfx, TextureFormat.RGBA8)
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

    override val windowWidth: Int get() = if (frameActive) savedWidth else platform.windowWidth

    override val windowHeight: Int get() = if (frameActive) savedHeight else platform.windowHeight

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
                logger.warn("Afterimage could not mux audio into $output", error)
                report.warn("Audio could not be muxed into the video: ${error.message}")
            }
        } catch (error: Throwable) {
            logger.warn("Afterimage could not render audio for $output", error)
            report.warn("Audio could not be rendered: ${error.message}")
        } finally {
            sounds.clips.clear()
        }
    }


    override fun prepare(settings: ExportSettings) {
        this.settings = settings
        exporting = true
        camera.exporting = true
        platform.calibrateChunks()
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
        previousHideGui = platform.setHideGui(true)
        workspace.close()
        onExportStarted()
        val supersample = settings.supersampleFactor
        val (passWidth, passHeight) = passSize(settings)
        renderWidth = passWidth * supersample
        renderHeight = passHeight * supersample
        exportTarget?.close()
        exportTarget = platform.createSurface(renderWidth, renderHeight, settings.transparent).also { it.setFilter(Filter.LINEAR) }
        val equirect = settings.projection == ExportProjection.EQUIRECTANGULAR
        val depthCapable =
            settings.projection == ExportProjection.PERSPECTIVE || settings.projection == ExportProjection.ORTHOGRAPHIC
        releaseBuffers()
        depthWanted = depthCapable && (settings.depthMap || settings.depthOfField)
        depthCopyFailed = false
        lookRequested = settings.lookActive
        depthReady = false
        sampleIndex = -1
        accumPending = false
        currentIndex = FrameSource.NO_FRAME
        pendingIndex = FrameSource.NO_FRAME
        pendingPost = false
        if (equirect) {
            passBuffers = Array(settings.projection.passes) { ByteArray(renderWidth * renderHeight * 4) }
            readback = BufferUtils.createByteBuffer(renderWidth * renderHeight * 4)
        } else {
            exportTarget!!.setFilter(Filter.LINEAR_MIPMAP)
            accumulation = gfx.createTarget(settings.width, settings.height, TextureFormat.RGBA16).also { accumulationReadback = gfx.createReadback(it, READBACK_SLOTS) }
            if (settings.lookActive && post.available) postBuffer = gfx.createTarget(settings.width, settings.height, TextureFormat.RGBA8).also { postReadback = gfx.createReadback(it, READBACK_SLOTS) }
            if (settings.depthMap && depthCapable) depthBuffer = gfx.createTarget(settings.width, settings.height, TextureFormat.R16).also { depthReadback = gfx.createReadback(it, READBACK_SLOTS) }
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
        savedWidth = platform.windowWidth
        savedHeight = platform.windowHeight
        frameActive = true
        platform.beginRender(target)
    }

    fun endRender() {
        if (!frameActive) return
        exportTarget?.makeOpaque()
        frameActive = false
        platform.endRender()
    }

    fun activeTarget(): ExportSurface? = if (frameActive) exportTarget else null

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
            processed?.color?.handle ?: target?.colorHandle ?: 0,
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
        (if (processed) postReadback else accumulationReadback)?.issue(currentSlot)
        depthBuffer?.let {
            if (!depthReady) gfx.clear(it, 0f, 0f, 0f, 1f)
            depthReadback?.issue(currentSlot)
        }
        pendingIndex = currentIndex
        pendingSlot = currentSlot
        pendingPost = processed
        currentSlot = (currentSlot + 1) % READBACK_SLOTS
        return delivered
    }

    private fun lookFrame(look: LookSettings, settings: ExportSettings): PostProcessor.Frame {
        val samples = settings.motionBlur.sampleCount
        return PostProcessor.Frame(
            look,
            frameFocus,
            PostProcessor.NEAR_PLANE,
            PostProcessor.farPlane(platform.viewDistance()),
            settings.projection == ExportProjection.ORTHOGRAPHIC,
            projectionScale(),
            (currentIndex % 4096L).toFloat(),
            maxOf(MIN_SAMPLE_TAPS, PostProcessor.EXPORT_TAPS / samples),
            PostProcessor.EXPORT_SPACING * settings.supersampleFactor,
            sampleIndex * SAMPLE_TAP_ROTATION,
        )
    }

    private fun runGrade(accumulation: Target, target: Target, settings: ExportSettings): Boolean {
        val look = settings.look ?: return false
        val color = accumulation.color ?: return false
        return post.draw(PostProcessor.Stage.GRADE, target, Viewport(0, 0, target.width, target.height), color, null, lookFrame(look, settings))
    }

    private fun runDepthOfField(target: ExportSurface, settings: ExportSettings) {
        val look = settings.look ?: return
        val depth = depthTexture.texture ?: return
        val source = target.asTarget()
        val color = scratch.ensure(renderWidth, renderHeight)
        gfx.copyColor(source, 0, 0, renderWidth, renderHeight, color)
        post.draw(PostProcessor.Stage.DEPTH_OF_FIELD, source, Viewport(0, 0, renderWidth, renderHeight), color, depth, lookFrame(look, settings))
    }

    private fun runDepthMap(target: Target, settings: ExportSettings): Boolean {
        val depth = depthTexture.texture ?: return false
        return post.drawDepth(
            target,
            Viewport(0, 0, target.width, target.height),
            depth,
            renderWidth,
            renderHeight,
            settings.supersampleFactor,
            PostProcessor.NEAR_PLANE,
            PostProcessor.farPlane(platform.viewDistance()),
            settings.projection == ExportProjection.ORTHOGRAPHIC,
            settings.depthRange.toFloat(),
        )
    }

    override fun flush(into: ByteArray, depthInto: FloatArray?): Long {
        val settings = this.settings ?: return FrameSource.NO_FRAME
        return collectPending(into, depthInto, settings)
    }

    private fun collectPending(into: ByteArray, depthInto: FloatArray?, settings: ExportSettings): Long {
        val index = pendingIndex
        if (index == FrameSource.NO_FRAME) return FrameSource.NO_FRAME
        val source = (if (pendingPost) postReadback else accumulationReadback) ?: return FrameSource.NO_FRAME
        source.collectRgba(pendingSlot, into)
        if (!settings.transparent) forceOpaque(into, settings)
        if (depthInto != null) {
            val depth = depthReadback
            if (depth != null) depth.collectR16(pendingSlot, depthInto) else Arrays.fill(depthInto, 0f)
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
            gfx.clear(accum, 0f, 0f, 0f, 0f)
        }
        val source = target.asTarget().color ?: return
        gfx.blit(
            source,
            accum,
            Viewport(viewport[0], viewport[1], viewport[2], viewport[3]),
            if (samples > 1) Blend.Accumulate(1f / samples) else Blend.None,
            Filter.LINEAR_MIPMAP,
        )
    }

    private fun releaseBuffers() {
        accumulationReadback?.close()
        accumulationReadback = null
        accumulation?.close()
        accumulation = null
        postReadback?.close()
        postReadback = null
        postBuffer?.close()
        postBuffer = null
        depthReadback?.close()
        depthReadback = null
        depthBuffer?.close()
        depthBuffer = null
        depthTexture.close()
        scratch.close()
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
        platform.setHideGui(previousHideGui)
        endRender()
        exportTarget?.close()
        exportTarget = null
        releaseBuffers()
        depthWanted = false
        depthReady = false
        accumPending = false
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
        gfx.preservingTarget {
            try {
                request.result = request.action()
            } catch (error: Throwable) {
                request.failure = error
            }
        }
        if (request.capturePass < 0) finish(request)
    }

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
            if (settings?.waitForChunks == true && !platform.chunksSettled()) {
                if (chunkWaitFrames < MAX_CHUNK_WAIT_FRAMES) {
                    chunkWaitFrames++
                    chunkWaitStatus = "waiting for chunks: " + platform.pendingChunkDescription()
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

    private fun missingSkins(): List<Int> = platform.missingSkins().filter { it !in skinGiveUp }

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
        if (depthCopyFailed) return
        if (!gfx.copyDepth(target.asTarget(), 0, 0, renderWidth, renderHeight, depthTexture.ensure(renderWidth, renderHeight))) {
            depthCopyFailed = true
            return
        }
        val depthMap = depthBuffer
        if (middle && depthMap != null) depthReady = runDepthMap(depthMap, settings)
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
        buffer.clear()
        gfx.readPixels(exportTarget?.asTarget(), 0, 0, width, height, buffer)
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
        const val READBACK_SLOTS = 2
    }
}
