package gg.sona.afterimage.mc26

import gg.sona.afterimage.capture.CaptureConfig
import gg.sona.afterimage.capture.CaptureListener
import gg.sona.afterimage.capture.CaptureStats
import gg.sona.afterimage.capture.packet.PacketCapture
import gg.sona.afterimage.capture.recording.RecordingStore
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.clip.ClipStore
import gg.sona.afterimage.core.event.Listeners
import gg.sona.afterimage.flashback.ClipRequest
import gg.sona.afterimage.flashback.EventDeriver
import gg.sona.afterimage.flashback.FlashbackEngine
import gg.sona.afterimage.flashback.GameProfile
import gg.sona.afterimage.flashback.Profiles
import gg.sona.afterimage.flashback.Trigger
import gg.sona.afterimage.mc.common.FlashbackClipListener
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import gg.sona.afterimage.format.AfterimageFormat
import gg.sona.afterimage.mc26.net.PacketIds26
import gg.sona.afterimage.mc26.net.PacketTaps26
import gg.sona.afterimage.mc26.protocol.Protocol26
import gg.sona.afterimage.mc26.state.ShadowClient26
import gg.sona.afterimage.protocol.InternalCodec
import gg.sona.afterimage.net.PackedPosition
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.net.PacketWriter
import gg.sona.afterimage.protocol.LocalBlockBreak
import gg.sona.afterimage.protocol.LocalBlockChange
import gg.sona.afterimage.protocol.LocalScreen
import gg.sona.afterimage.protocol.LocalTarget
import gg.sona.afterimage.protocol.AfterimageInternal
import gg.sona.afterimage.protocol.ServerboundPacket
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import gg.sona.afterimage.protocol.SessionMark
import gg.sona.afterimage.world.RecorderIdentity
import gg.sona.afterimage.mc26.mixin.ConnectionAccessor
import io.netty.channel.Channel
import net.minecraft.client.Minecraft
import net.minecraft.network.Connection
import org.slf4j.LoggerFactory
import java.nio.file.Path

class RecordingController26(private val minecraft: Minecraft, private val store: RecordingStore, private val clips: ClipStore) {
    private class Live(val channel: Channel, val capture: PacketCapture, val state: ShadowClient26, val deriver: EventDeriver, val engine: FlashbackEngine)

    val flashbackListeners = Listeners<FlashbackClipListener>()
    val availableProfiles: List<GameProfile> = Profiles.all()

    @Volatile
    var enabledProfileIds: Set<String> = availableProfiles.map { it.id }.toSet()
        set(value) {
            field = value
            live?.engine?.profiles = profiles
        }
    val profiles: List<GameProfile> get() = availableProfiles.filter { it.id in enabledProfileIds }
    var instantClipSeconds: Int = 30
    var cameraSampleRate: Int
        get() = camera.sampleRate
        set(value) {
            camera.sampleRate = value
        }
    val shadow: ShadowClient26? get() = live?.state
    private val inventoryCapture = InventoryCapture26(minecraft) { packet ->
        val session = live?.takeIf { it.capture.isRecording } ?: return@InventoryCapture26 Unit
        val encoded = session.state.codecs.encode(packet, 0L) ?: return@InventoryCapture26 Unit
        session.capture.offer(PacketDirection.CLIENTBOUND, encoded.packetId, encoded.payload)
    }

    private val logger = LoggerFactory.getLogger("Afterimage")
    private var live: Live? = null
    private val writer = PacketWriter()
    private var lastScreen = LocalScreen.CLOSED
    private var lastTarget = LocalTarget.NONE
    private var lastBreakPosition = 0L
    private var lastBreakStage = -1
    val camera = CameraCapture26(minecraft).also { it.capture = { live?.capture } }

    var keyframeIntervalNanos: Long = Nanos.ofSeconds(10)
    var autoRecord: Boolean = false

    val isRecording: Boolean get() = live?.capture?.isRecording == true
    val isConnected: Boolean get() = live != null
    val currentRecording: Path? get() = live?.capture?.currentRecordingPath
    var lastStats: CaptureStats? = null
        private set

    fun identity(): RecorderIdentity {
        val user = minecraft.user
        return RecorderIdentity(user.profileId, user.name)
    }

    fun onConnection(connection: Connection) {
        val channel = (connection as ConnectionAccessor).afterimage_channel() ?: return
        if (live?.channel === channel) return
        onDisconnect()
        val state = ShadowClient26(identity())
        val capture = PacketCapture(CaptureConfig(keyframeIntervalNanos = keyframeIntervalNanos), { state }, protocolVersion = PacketIds26.protocolVersion)
        val deriver = EventDeriver(state.world)
        state.liveEvents = deriver
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
        live = Live(channel, capture, state, deriver, engine)
        PacketTaps26.install(channel, capture)
        if (autoRecord) startRecording()
    }

    fun onDisconnect() {
        val session = live ?: return
        live = null
        if (session.capture.isRecording) {
            runCatching { session.capture.mark(InternalCodec.encode(SessionMark(SessionMark.DISCONNECTED, ""), 0L)) }
            runCatching { session.capture.stopRecording().await() }
        }
        runCatching { PacketTaps26.remove(session.channel) }
        runCatching { session.capture.close() }.onFailure { logger.error("Afterimage failed to close capture", it) }
    }

    fun onClientTickStart() {
        val session = live ?: return
        if (!session.capture.isRecording || minecraft.level == null) return
        session.capture.offer(PacketDirection.SERVERBOUND, AfterimageInternal.LOCAL_TICK, EMPTY_PAYLOAD)
    }

    fun onClientTick() = Unit

    fun onFrame() {
        val session = live ?: return
        if (!session.capture.isRecording) return
        val allowMouse = camera.sampleAccepted
        sampleScreen(session, allowMouse)
        if (allowMouse) sampleTarget(session)
    }

    private fun sampleTarget(session: Live) {
        val hit = minecraft.hitResult
        val target = if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
            LocalTarget(PackedPosition.pack(hit.blockPos.x, hit.blockPos.y, hit.blockPos.z), hit.direction.get3DDataValue())
        } else LocalTarget.NONE
        if (target == lastTarget) return
        lastTarget = target
        offer(session, target)
    }

    private fun sampleScreen(session: Live, allowMouse: Boolean) {
        val current = ScreenCapture26.capture(minecraft)
        if (!current.isOpen && !lastScreen.isOpen && current.flags == lastScreen.flags) return
        if (current.sameLayout(lastScreen)) {
            if (!allowMouse) return
            if (current.mouseX == lastScreen.mouseX && current.mouseY == lastScreen.mouseY && current.guiWidth == lastScreen.guiWidth && current.guiHeight == lastScreen.guiHeight) return
        }
        lastScreen = current
        offer(session, current)
    }

    fun onLocalBlockChange(pos: BlockPos, state: BlockState) {
        val session = live ?: return
        if (!session.capture.isRecording) return
        val id = Block.getId(state)
        if (id < 0) return
        offer(session, LocalBlockChange(PackedPosition.pack(pos.x, pos.y, pos.z), id))
    }

    fun onBlockMiningProgress(breakerId: Int, pos: BlockPos, stage: Int) {
        val session = live ?: return
        if (!session.capture.isRecording) return
        val player = minecraft.player ?: return
        if (breakerId != player.id) return
        val position = PackedPosition.pack(pos.x, pos.y, pos.z)
        val clamped = if (stage in 0..9) stage else -1
        if (clamped < 0 && position == lastBreakPosition && clamped == lastBreakStage) return
        lastBreakPosition = position
        lastBreakStage = clamped
        offer(session, LocalBlockBreak(position, clamped))
    }

    private fun offer(session: Live, packet: ServerboundPacket) {
        writer.reset()
        InternalCodec.encodeInto(packet, writer)
        session.capture.offer(PacketDirection.SERVERBOUND, packet.packetId, writer.toByteArray())
    }

    fun startRecording(): Boolean {
        val session = live ?: return false
        if (session.capture.isRecording) return false
        val metadata = LinkedHashMap<String, String>()
        val identity = identity()
        identity.name?.let { metadata[AfterimageFormat.MetadataKeys.PLAYER_NAME] = it }
        identity.uuid?.let { metadata[AfterimageFormat.MetadataKeys.PLAYER_UUID] = it.toString() }
        minecraft.currentServer?.ip?.let { metadata[AfterimageFormat.MetadataKeys.SERVER_ADDRESS] = it }
        metadata[AfterimageFormat.MetadataKeys.MINECRAFT_VERSION] = Protocol26.MINECRAFT_VERSION
        metadata[AfterimageFormat.MetadataKeys.AFTERIMAGE_VERSION] = AfterimageRuntime26.VERSION
        session.engine.reset()
        camera.reset()
        lastScreen = LocalScreen.CLOSED
        lastTarget = LocalTarget.NONE
        lastBreakPosition = 0L
        lastBreakStage = -1
        session.capture.startRecording(store.newTarget(metadata))
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
        session.capture.mark(InternalCodec.encode(SessionMark(SessionMark.USER_MARKER, label), 0L))
    }

    fun stats(): CaptureStats? = live?.capture?.stats()

    fun beforeSlotClick(screen: AbstractContainerScreen<*>) {
        if (live?.capture?.isRecording != true) return
        inventoryCapture.before(screen)
    }

    fun afterSlotClick(screen: AbstractContainerScreen<*>) {
        if (live == null) return
        inventoryCapture.after(screen)
    }

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
        session.capture.mark(InternalCodec.encode(SessionMark(SessionMark.USER_MARKER, title), 0L))
        flashbackListeners.dispatch { it.onFlashbackClip(request, clip) }
        return clip
    }

    private fun onClipRequest(capture: PacketCapture, request: ClipRequest) {
        val info = capture.recordingInfo() ?: return
        val clip = request.toClip(info.path, info.sessionId, info.originNanos)
        runCatching { clips.save(clip) }.onFailure { logger.warn("Afterimage could not persist flashback clip", it) }
        logger.info("Afterimage flashback: {} at {} ms", clip.title, clip.startNanos / 1_000_000L)
        flashbackListeners.dispatch { it.onFlashbackClip(request, clip) }
    }

    fun shutdown() = onDisconnect()

    private companion object {
        val EMPTY_PAYLOAD = ByteArray(0)
    }
}
