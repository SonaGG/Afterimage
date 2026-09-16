package gg.sona.afterimage.mc189

import gg.sona.afterimage.mc189.replay.shadow
import gg.sona.afterimage.mc189.net.PacketTaps
import gg.sona.afterimage.mc.common.FlashbackClipListener
import gg.sona.afterimage.capture.CaptureConfig
import gg.sona.afterimage.capture.CaptureListener
import gg.sona.afterimage.capture.CaptureStats
import gg.sona.afterimage.capture.packet.PacketCapture
import gg.sona.afterimage.capture.recording.RecordingStore
import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.clip.ClipStore
import gg.sona.afterimage.core.event.Listeners
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.flashback.*
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.net.PacketWriter
import gg.sona.afterimage.protocol.*
import gg.sona.afterimage.mc189.state.ShadowClient
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen
import net.minecraft.network.Connection
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket
import net.minecraft.world.HitResult
import org.apache.logging.log4j.LogManager
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class RecordingController(
    private val platform: MinecraftPlatform,
    private val store: RecordingStore,
    private val clips: ClipStore,
) {

    private class Live(
        val channel: Channel,
        val capture: PacketCapture,
        val shadow: ShadowClient,
        val deriver: EventDeriver,
        val engine: FlashbackEngine,
    )

    private val logger = LogManager.getLogger("Afterimage")
    private val poseWriter = PacketWriter(64)
    private var lastBreakPosition = Long.MIN_VALUE
    private var lastBreakStage = Int.MIN_VALUE
    private var lastScreen = LocalScreen.CLOSED
    private var lastTarget = LocalTarget.NONE
    private val inventoryCapture = InventoryCapture(platform.minecraft) { direction, id, payload ->
        live?.takeIf { it.capture.isRecording }?.capture?.offer(
            direction,
            id,
            payload
        )
    }
    val flashbackListeners = Listeners<FlashbackClipListener>()

    @Volatile
    private var live: Live? = null

    private var lastPoseYaw = Float.NaN
    private var lastSampleNanos = Long.MIN_VALUE
    var cameraSampleRate: Int = 120
    private var lastPosePitch = Float.NaN
    private var lastPoseX = Double.NaN
    private var lastPoseY = Double.NaN
    private var lastPoseZ = Double.NaN
    private val matrixBuffer = BufferUtils.createFloatBuffer(16)
    private val lastFrameMatrix = FloatArray(16) { Float.NaN }
    private var lastFrameFov = Float.NaN
    private val lastFramePosition = DoubleArray(3) { Double.NaN }
    private val lastHandMatrix = FloatArray(16) { Float.NaN }
    private val pendingView = FloatArray(16)
    private val pendingPosition = DoubleArray(3)
    private val pendingHand = FloatArray(16)
    private var viewCaptured = false
    private var handCaptured = false
    private val frameWriter = PacketWriter(192)

    @Volatile
    private var currentFov = 70f

    var autoRecord: Boolean = true

    @Volatile
    var keyframeIntervalNanos: Long = CaptureConfig().keyframeIntervalNanos

    val availableProfiles: List<GameProfile> = Profiles.all()

    @Volatile
    var enabledProfileIds: Set<String> = availableProfiles.map { it.id }.toSet()
        set(value) {
            field = value
            live?.engine?.profiles = profiles
        }

    val profiles: List<GameProfile> get() = availableProfiles.filter { it.id in enabledProfileIds }

    var lastStats: CaptureStats? = null
        private set

    val isRecording: Boolean get() = live?.capture?.isRecording == true

    val isConnected: Boolean get() = live != null

    val currentRecording: Path? get() = live?.capture?.currentRecordingPath

    val shadow: ShadowClient? get() = live?.shadow

    fun onPlayConnection(connection: Connection) {
        onDisconnect()
        val channel = connection.channel ?: return
        val shadow = ShadowClient(platform.identity())
        val capture = PacketCapture(CaptureConfig(keyframeIntervalNanos = keyframeIntervalNanos), { shadow })
        val deriver = EventDeriver(shadow.world)
        shadow.liveEvents = deriver
        val engine = FlashbackEngine(profiles)
        deriver.listeners.add(engine)
        engine.listeners.add { request -> onClipRequest(capture, request) }
        capture.listeners.add(object : CaptureListener {
            override fun onRecordingStopped(path: Path, stats: CaptureStats) {
                lastStats = stats
            }

            override fun onFailure(stage: String, error: Throwable) {
                logger.error("Afterimage capture stage {} failed", stage, error)
            }
        })
        val session = Live(channel, capture, shadow, deriver, engine)
        live = session
        PacketTaps.install(channel, capture)
        if (autoRecord) startRecording()
    }

    fun onCompressionChanged(channel: Channel) = PacketTaps.reposition(channel)

    fun onConnectionClosed(connection: Connection) {
        val session = live ?: return
        if (connection.channel === session.channel) onDisconnect()
    }

    fun onClientTick() {
        val session = live ?: return
        if (!session.channel.isOpen) onDisconnect()
    }

    fun onClientTickStart() {
        val session = live ?: return
        if (!session.capture.isRecording || platform.minecraft.world == null) return
        session.capture.offer(PacketDirection.SERVERBOUND, AfterimageInternal.LOCAL_TICK, EMPTY_PAYLOAD)
    }

    fun onDisconnect() {
        val session = live ?: return
        live = null
        runCatching { session.capture.mark(PacketCodec.encode(SessionMark(SessionMark.DISCONNECTED, ""), 0L)) }
        runCatching { PacketTaps.remove(session.channel) }
        runCatching { session.capture.close() }.onFailure { logger.error("Afterimage failed to close capture", it) }
    }

    fun startRecording(): Boolean {
        val session = live ?: return false
        if (session.capture.isRecording) return false
        session.engine.reset()
        val target = store.newTarget(platform.recordingMetadata())
        session.capture.startRecording(target)
        resetPoseSampling()
        return true
    }

    fun stopRecording(): Boolean {
        val session = live ?: return false
        if (!session.capture.isRecording) return false
        session.capture.stopRecording()
        return true
    }

    fun mark(label: String) {
        val session = live ?: return
        session.capture.mark(PacketCodec.encode(SessionMark(SessionMark.USER_MARKER, label), 0L))
    }

    var instantClipSeconds: Int = 30

    fun instantClip(): Clip? {
        val session = live ?: return null
        if (!session.capture.isRecording) return null
        val capture = session.capture
        val now = capture.clock.nanos()
        val profile = profiles.firstOrNull() ?: availableProfiles.first()
        val title = "Instant replay " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val trigger = Trigger("instant", now, title, setOf("instant"), Nanos.ofSeconds(instantClipSeconds.toLong()), 0L)
        val request = ClipRequest(trigger, profile)
        val info = capture.recordingInfo() ?: return null
        val clip = request.toClip(info.path, info.sessionId, info.originNanos)
        runCatching { clips.save(clip) }.onFailure { logger.warn("Afterimage could not persist instant clip", it) }
        session.capture.mark(PacketCodec.encode(SessionMark(SessionMark.USER_MARKER, title), 0L))
        flashbackListeners.dispatch { it.onFlashbackClip(request, clip) }
        return clip
    }

    private var registeredChannel: Any? = null

    private fun registerChannel(player: LocalClientPlayerEntity) {
        val handler = player.networkHandler
        if (registeredChannel === handler) return
        registeredChannel = handler
        runCatching {
            val buffer = PacketByteBuf(Unpooled.wrappedBuffer(AfterimageChannel.registrationPayload()))
            handler.sendPacket(CustomPayloadC2SPacket(AfterimageChannel.REGISTER_CHANNEL, buffer))
        }.onFailure { logger.warn("Afterimage could not register the ${AfterimageChannel.NAME} channel", it) }
    }

    private fun sampleDue(): Boolean {
        if (cameraSampleRate <= 0) return true
        val now = System.nanoTime()
        val interval = 1_000_000_000L / cameraSampleRate
        if (lastSampleNanos != Long.MIN_VALUE && now - lastSampleNanos < interval) return false
        lastSampleNanos =
            if (lastSampleNanos == Long.MIN_VALUE || now - lastSampleNanos > interval * 2) now else lastSampleNanos + interval
        return true
    }

    private var sampleFrameAccepted = false

    fun onFrame(tickDelta: Float) {
        val session = live ?: return
        val player = platform.minecraft.player ?: return
        registerChannel(player)
        if (!session.capture.isRecording) return
        sampleFrameAccepted = sampleDue()
        sampleScreen(session, sampleFrameAccepted)
        if (!sampleFrameAccepted) return
        sampleTarget(session)
        val x = player.prevX + (player.x - player.prevX) * tickDelta
        val y = player.prevY + (player.y - player.prevY) * tickDelta
        val z = player.prevZ + (player.z - player.prevZ) * tickDelta
        val yaw = player.lastYaw + (player.yaw - player.lastYaw) * tickDelta
        val pitch = player.lastPitch + (player.pitch - player.lastPitch) * tickDelta
        if (yaw == lastPoseYaw && pitch == lastPosePitch && x == lastPoseX && y == lastPoseY && z == lastPoseZ) return
        lastPoseYaw = yaw
        lastPosePitch = pitch
        lastPoseX = x
        lastPoseY = y
        lastPoseZ = z
        poseWriter.reset()
        PacketCodec.encodeInto(LocalPose(x, y, z, yaw, pitch), poseWriter)
        session.capture.offer(PacketDirection.SERVERBOUND, AfterimageInternal.LOCAL_POSE, poseWriter.toByteArray())
    }

    fun beforeSlotClick(screen: InventoryMenuScreen) {
        if (live?.capture?.isRecording != true) return
        inventoryCapture.before(screen)
    }

    fun afterSlotClick(screen: InventoryMenuScreen) {
        if (live == null) return
        inventoryCapture.after(screen)
    }

    fun onLocalBlockChange(position: Long, state: Int) {
        val session = live ?: return
        if (!session.capture.isRecording) return
        poseWriter.reset()
        PacketCodec.encodeInto(LocalBlockChange(position, state), poseWriter)
        session.capture.offer(PacketDirection.SERVERBOUND, AfterimageInternal.LOCAL_BLOCK_CHANGE, poseWriter.toByteArray())
    }

    private fun sampleTarget(session: Live) {
        val hit = platform.minecraft.crosshairTarget
        val face = hit?.face
        val target = if (hit != null && hit.type == HitResult.Type.BLOCK && face != null) LocalTarget(
            hit.pos.toLong(),
            face.id
        ) else LocalTarget.NONE
        if (target == lastTarget) return
        lastTarget = target
        poseWriter.reset()
        PacketCodec.encodeInto(target, poseWriter)
        session.capture.offer(PacketDirection.SERVERBOUND, AfterimageInternal.LOCAL_TARGET, poseWriter.toByteArray())
    }

    private fun sampleScreen(session: Live, allowMouse: Boolean) {
        val current = ScreenCapture.capture(platform.minecraft)
        if (!current.isOpen && !lastScreen.isOpen && current.flags == lastScreen.flags) return
        if (current.sameLayout(lastScreen)) {
            if (!allowMouse) return
            if (current.mouseX == lastScreen.mouseX && current.mouseY == lastScreen.mouseY && current.guiWidth == lastScreen.guiWidth && current.guiHeight == lastScreen.guiHeight) return
        }
        lastScreen = current
        poseWriter.reset()
        PacketCodec.encodeInto(current, poseWriter)
        session.capture.offer(PacketDirection.SERVERBOUND, AfterimageInternal.LOCAL_SCREEN, poseWriter.toByteArray())
    }

    fun onBlockMiningProgress(breakerId: Int, position: Long, stage: Int) {
        val session = live ?: return
        if (!session.capture.isRecording) return
        val player = platform.minecraft.player ?: return
        if (breakerId != player.networkId) return
        val clamped = if (stage in 0..9) stage else -1
        if (clamped < 0 && position == lastBreakPosition && clamped == lastBreakStage) return
        lastBreakPosition = position
        lastBreakStage = clamped
        poseWriter.reset()
        PacketCodec.encodeInto(LocalBlockBreak(position, clamped), poseWriter)
        session.capture.offer(PacketDirection.SERVERBOUND, AfterimageInternal.LOCAL_BLOCK_BREAK, poseWriter.toByteArray())
    }

    fun observeFov(value: Float) {
        currentFov = value
    }

    fun onCameraSetup(tickDelta: Float) {
        val session = live ?: return
        if (!session.capture.isRecording || !sampleFrameAccepted) return
        if (platform.minecraft.player == null) return
        val view = platform.minecraft.camera ?: platform.minecraft.player ?: return
        matrixBuffer.clear()
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, matrixBuffer)
        matrixBuffer.get(pendingView)
        pendingPosition[0] = view.lastX + (view.x - view.lastX) * tickDelta
        pendingPosition[1] = view.lastY + (view.y - view.lastY) * tickDelta
        pendingPosition[2] = view.lastZ + (view.z - view.lastZ) * tickDelta
        viewCaptured = true
        handCaptured = false
    }

    fun onHandSetup() {
        if (!viewCaptured) return
        matrixBuffer.clear()
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, matrixBuffer)
        matrixBuffer.get(pendingHand)
        handCaptured = true
    }

    fun onFrameEnd() {
        if (!viewCaptured) return
        viewCaptured = false
        val session = live ?: return
        if (!session.capture.isRecording) return
        var changed = currentFov != lastFrameFov
        for (index in 0 until 16) {
            if (pendingView[index] != lastFrameMatrix[index]) {
                lastFrameMatrix[index] = pendingView[index]
                changed = true
            }
        }
        for (index in 0 until 3) {
            if (pendingPosition[index] != lastFramePosition[index]) {
                lastFramePosition[index] = pendingPosition[index]
                changed = true
            }
        }
        if (handCaptured) {
            for (index in 0 until 16) {
                if (pendingHand[index] != lastHandMatrix[index]) {
                    lastHandMatrix[index] = pendingHand[index]
                    changed = true
                }
            }
        } else if (!lastHandMatrix[0].isNaN()) {
            lastHandMatrix.fill(Float.NaN)
            changed = true
        }
        if (!changed) return
        lastFrameFov = currentFov
        frameWriter.reset()
        val hand = if (handCaptured) pendingHand.copyOf() else null
        PacketCodec.encodeInto(
            CameraFrame(pendingView.copyOf(), currentFov, pendingPosition.copyOf(), hand),
            frameWriter
        )
        session.capture.offer(
            PacketDirection.SERVERBOUND,
            AfterimageInternal.CAMERA_FRAME_COMPACT,
            frameWriter.toByteArray()
        )
    }

    fun stats(): CaptureStats? = live?.capture?.stats()

    fun shutdown() = onDisconnect()

    private fun onClipRequest(capture: PacketCapture, request: ClipRequest) {
        val info = capture.recordingInfo() ?: return
        val clip = request.toClip(info.path, info.sessionId, info.originNanos)
        runCatching { clips.save(clip) }.onFailure { logger.warn("Afterimage could not persist flashback clip", it) }
        logger.info("Afterimage flashback: {} at {} ms", clip.title, clip.startNanos / 1_000_000L)
        flashbackListeners.dispatch { it.onFlashbackClip(request, clip) }
    }

    private fun resetPoseSampling() {
        lastFrameMatrix.fill(Float.NaN)
        lastFrameFov = Float.NaN
        lastFramePosition.fill(Double.NaN)
        lastHandMatrix.fill(Float.NaN)
        viewCaptured = false
        handCaptured = false
        lastPoseYaw = Float.NaN
        lastPosePitch = Float.NaN
        lastPoseX = Double.NaN
        lastPoseY = Double.NaN
        lastPoseZ = Double.NaN
        lastScreen = LocalScreen.CLOSED
        lastTarget = LocalTarget.NONE
    }

    private companion object {
        val EMPTY_PAYLOAD = ByteArray(0)
    }
}
