package gg.sona.recast.replay.session

import gg.sona.recast.core.event.Listeners
import gg.sona.recast.core.log.RecastLog
import gg.sona.recast.core.time.Nanos
import gg.sona.recast.format.RecastFormat
import gg.sona.recast.format.RecordingHeader
import gg.sona.recast.format.SegmentInfo
import gg.sona.recast.format.SegmentKind
import gg.sona.recast.net.CapturedPacket
import gg.sona.recast.net.PacketDirection
import gg.sona.recast.protocol.*
import gg.sona.recast.replay.consumer.DeliveryMode
import gg.sona.recast.replay.consumer.ReplayConsumer
import gg.sona.recast.replay.consumer.ResetReason
import gg.sona.recast.replay.source.ReplaySource
import gg.sona.recast.replay.state.camera.CameraSample
import gg.sona.recast.replay.state.diff.StateDiff
import gg.sona.recast.replay.state.shadow.RecorderIdentity
import gg.sona.recast.replay.state.shadow.ShadowClient
import java.util.*
import kotlin.math.abs

class ReplaySession(
    val source: ReplaySource,
    consumers: List<ReplayConsumer> = emptyList(),
    private val options: ReplayOptions = ReplayOptions(),
    val shadow: ShadowClient = ShadowClient(identityOf(source)),
    private val diffSeeks: Boolean = true,
) : AutoCloseable {

    private val logger = RecastLog.logger("recast.replay")
    private val consumers = ArrayList<ReplayConsumer>().apply {
        add(shadow)
        addAll(consumers)
    }
    private val jumpThreshold =
        if (options.keyframeJumpThresholdNanos >= 0) options.keyframeJumpThresholdNanos else source.header.keyframeIntervalNanos

    val listeners = Listeners<ReplayListener>()

    val startNanos: Long get() = source.startNanos

    val endNanos: Long get() = source.endNanos

    val durationNanos: Long get() = endNanos - startNanos

    val header: RecordingHeader get() = source.header

    var positionNanos: Long = source.startNanos
        private set

    var speed: Double = 1.0
        set(value) {
            val magnitude = abs(value).coerceIn(MIN_SPEED, MAX_SPEED)
            val clamped = if (value < 0) -magnitude else magnitude
            if (field == clamped) return
            field = clamped
            listeners.dispatch { it.onSpeedChanged(clamped) }
        }

    var playing: Boolean = false
        private set

    var packetsDelivered: Long = 0L
        private set

    private var segmentIndex = -1
    private var packetIndex = 0
    private var current: List<CapturedPacket>? = null
    private var loaded = false
    private var scratch: ReplaySession? = null

    fun addConsumer(consumer: ReplayConsumer) {
        consumers += consumer
    }

    fun removeConsumer(consumer: ReplayConsumer) {
        consumers.remove(consumer)
    }

    fun load() {
        if (loaded) return
        loaded = true
        rebuildFromKeyframe(startNanos, ResetReason.LOAD)
        deliverUntil(startNanos, DeliveryMode.SEEK)
    }

    fun play() {
        load()
        if (playing) return
        if (positionNanos >= endNanos) seek(startNanos)
        playing = true
        listeners.dispatch { it.onPlayingChanged(true) }
    }

    fun pause() {
        if (!playing) return
        playing = false
        listeners.dispatch { it.onPlayingChanged(false) }
    }

    fun togglePlaying() = if (playing) pause() else play()

    fun seek(targetNanos: Long, linear: Boolean = false) {
        load()
        val target = targetNanos.coerceIn(minOf(earliestSeekable(), endNanos), endNanos)
        if (target == positionNanos) return
        val started = System.nanoTime()
        try {
            if (target >= positionNanos && (linear || !shouldJump(target))) {
                deliverUntil(target, DeliveryMode.SEEK)
                return
            }
            if (diffSeeks && shadow.joined && seekByDiff(target)) return
            rebuildFromKeyframe(target, ResetReason.SEEK)
            deliverUntil(target, DeliveryMode.SEEK)
        } finally {
            lastSeekDurationNanos = System.nanoTime() - started
            seeks++
        }
    }

    fun advanceTo(targetNanos: Long) {
        load()
        val target = targetNanos.coerceIn(minOf(earliestSeekable(), endNanos), endNanos)
        if (target == positionNanos) return
        if (target < positionNanos || shouldJump(target)) {
            seek(target)
            return
        }
        deliverUntil(target, DeliveryMode.LIVE)
    }

    var lastSeekDurationNanos: Long = 0L
        private set

    var seeks: Long = 0L
        private set

    var lastDiffPackets: Int = 0
        private set

    private fun seekByDiff(target: Long): Boolean {
        val scratch = scratchSession()
        synchronized(scratch) {
            scratch.seek(target)
            if (!StateDiff.canDiff(shadow, scratch.shadow)) return false
            shadow.localPlayer.cameraFrames.dropAfter(minOf(target, positionNanos))
            val diff = StateDiff.packets(shadow, scratch.shadow, target, positionNanos)
            lastDiffPackets = diff.size
            for (packet in PacketCodec.encodeAll(diff, target)) deliver(packet, DeliveryMode.SEEK)
            segmentIndex = scratch.segmentIndex
            packetIndex = scratch.packetIndex
            current = scratch.current
            positionNanos = target
            if (hasTickMarkers()) worldTickNanos = scratch.worldTickNanos else alignWorldTick(target)
            shadow.localPlayer.cameraFrames.copyFrom(scratch.shadow.localPlayer.cameraFrames)
            shadow.localPlayer.cameraFrames.dropAfter(target)
            primeCameraLookahead(target)
        }
        consumers.forEach { it.onSettled(target, DeliveryMode.SEEK) }
        listeners.dispatch { it.onPositionChanged(target, DeliveryMode.SEEK) }
        return true
    }

    private fun scratchSession(): ReplaySession =
        scratch ?: ReplaySession(
            source,
            emptyList(),
            options.copy(loop = false),
            ShadowClient(shadow.recorderIdentity),
            diffSeeks = false
        ).also { scratch = it }

    fun seekRelative(deltaNanos: Long) = seek(positionNanos + deltaNanos)

    fun stepTicks(ticks: Int) = seek(positionNanos + ticks * Nanos.PER_TICK)

    fun advance(realDeltaNanos: Long) {
        load()
        if (!playing || realDeltaNanos <= 0L) return
        val scaled = (realDeltaNanos * speed).toLong()
        val target = positionNanos + scaled
        if (speed < 0) {
            if (target <= startNanos) {
                seek(startNanos)
                pause()
                listeners.dispatch { it.onReachedEnd() }
            } else {
                seek(target)
            }
            return
        }
        if (target >= endNanos) {
            deliverUntil(endNanos, DeliveryMode.LIVE)
            if (options.loop) {
                seek(startNanos)
            } else {
                pause()
                listeners.dispatch { it.onReachedEnd() }
            }
            return
        }
        deliverUntil(target, DeliveryMode.LIVE)
    }

    override fun close() {
        pause()
        consumers.forEach { it.onReset(ResetReason.CLOSE) }
        scratch?.let { it.consumers.forEach { consumer -> consumer.onReset(ResetReason.CLOSE) } }
        source.close()
    }

    private var earliestNanos = Long.MIN_VALUE

    private fun usableSnapshot(nanos: Long): SegmentInfo? =
        source.snapshotAtOrBefore(nanos)?.takeIf { it.packetCount > 0 }

    private fun earliestSeekable(): Long {
        if (earliestNanos != Long.MIN_VALUE) return earliestNanos
        var result = startNanos
        if (usableSnapshot(startNanos) == null) {
            val first = source.segments.firstOrNull { it.kind != SegmentKind.SNAPSHOT }
            if (first != null) {
                val join = source.packets(first.index)
                    .firstOrNull { it.direction == PacketDirection.CLIENTBOUND && it.packetId == ClientboundPlay.JOIN_GAME }
                if (join != null) result = maxOf(result, join.timestampNanos)
            }
        }
        earliestNanos = result
        return result
    }

    private fun shouldJump(target: Long): Boolean {
        val snapshot = usableSnapshot(target) ?: return false
        return snapshot.index > segmentIndex && target - positionNanos > jumpThreshold
    }

    private fun rebuildFromKeyframe(target: Long, reason: ResetReason) {
        val snapshot = usableSnapshot(target)
        consumers.forEach { it.onReset(reason) }
        current = null
        packetIndex = 0
        if (snapshot != null) {
            for (packet in source.packets(snapshot.index)) deliver(packet, DeliveryMode.SEEK)
            segmentIndex = snapshot.index
            positionNanos = snapshot.startNanos
            alignWorldTick(snapshot.startNanos)
        } else {
            segmentIndex = -1
            positionNanos = startNanos
            alignWorldTick(startNanos)
        }
    }

    var tickHook: ((Long) -> Unit)? = null
    private var worldTickNanos = Long.MIN_VALUE
    private var tickMarkers: Boolean? = null

    val lastTickNanos: Long get() = if (worldTickNanos == Long.MIN_VALUE) positionNanos else worldTickNanos

    val recordedTicks: Boolean get() = hasTickMarkers()

    private fun hasTickMarkers(): Boolean {
        tickMarkers?.let { return it }
        var found = false
        var scanned = 0
        var firstNanos = Long.MIN_VALUE
        for ((index, _, kind) in source.segments) {
            if (kind == SegmentKind.SNAPSHOT) continue
            val packets = source.packets(index)
            if (packets.any { it.direction == PacketDirection.SERVERBOUND && it.packetId == RecastInternal.LOCAL_TICK }) {
                found = true
                break
            }
            if (firstNanos == Long.MIN_VALUE) firstNanos = packets.firstOrNull()?.timestampNanos ?: Long.MIN_VALUE
            val last = packets.lastOrNull()?.timestampNanos ?: Long.MIN_VALUE
            if (++scanned >= MARKER_SCAN_SEGMENTS || (firstNanos != Long.MIN_VALUE && last - firstNanos > MARKER_SCAN_NANOS)) break
        }
        tickMarkers = found
        return found
    }

    private fun alignWorldTick(nanos: Long) {
        worldTickNanos = if (hasTickMarkers()) nanos else Math.floorDiv(nanos, TICK_NANOS) * TICK_NANOS
    }

    private fun fireTick(nanos: Long) {
        worldTickNanos = nanos
        val hook = tickHook ?: return
        try {
            hook(nanos)
        } catch (error: Throwable) {
            logger.error("Replay tick hook failed at $nanos", error)
        }
    }

    private fun tickUpTo(nanos: Long) {
        if (worldTickNanos == Long.MIN_VALUE) alignWorldTick(positionNanos)
        val hook = tickHook
        if (hook == null) {
            if (nanos > worldTickNanos) alignWorldTick(nanos)
            return
        }
        var next = worldTickNanos + TICK_NANOS
        while (next <= nanos) {
            worldTickNanos = next
            try {
                hook(next)
            } catch (error: Throwable) {
                logger.error("Replay world tick failed at $next", error)
            }
            next += TICK_NANOS
        }
    }

    private fun deliverUntil(target: Long, mode: DeliveryMode) {
        val markers = hasTickMarkers()
        while (true) {
            val packets = current ?: loadNextSegment() ?: break
            if (packetIndex >= packets.size) {
                current = null
                continue
            }
            val packet = packets[packetIndex]
            if (packet.timestampNanos > target) break
            if (skipDimensionBounce(packets)) continue
            if (markers) {
                if (packet.direction == PacketDirection.SERVERBOUND && packet.packetId == RecastInternal.LOCAL_TICK) fireTick(
                    packet.timestampNanos
                )
            } else {
                tickUpTo(packet.timestampNanos)
            }
            deliver(packet, mode)
            packetIndex++
        }
        if (!markers) tickUpTo(target)
        positionNanos = target
        primeCameraLookahead(target)
        consumers.forEach { it.onSettled(target, mode) }
        listeners.dispatch { it.onPositionChanged(target, mode) }
    }

    private fun skipDimensionBounce(packets: List<CapturedPacket>): Boolean {
        val leaving = packets[packetIndex]
        if (leaving.direction != PacketDirection.CLIENTBOUND || leaving.packetId != ClientboundPlay.RESPAWN) return false
        val returning = packets.getOrNull(packetIndex + 1) ?: return false
        if (returning.direction != PacketDirection.CLIENTBOUND || returning.packetId != ClientboundPlay.RESPAWN) return false
        if (returning.timestampNanos - leaving.timestampNanos > DIMENSION_BOUNCE_WINDOW_NANOS) return false
        val currentDimension = shadow.world.dimension
        val leavingRespawn = PacketCodec.decode(leaving) as? Respawn ?: return false
        if (leavingRespawn.dimension == currentDimension) return false
        val returningRespawn = PacketCodec.decode(returning) as? Respawn ?: return false
        if (returningRespawn.dimension != currentDimension) return false
        packetIndex += 2
        return true
    }

    private fun primeCameraLookahead(target: Long) {
        var list = current ?: return
        var index = packetIndex
        var segment = segmentIndex
        val limit = target + CAMERA_LOOKAHEAD_NANOS
        val frames = shadow.localPlayer.cameraFrames
        var scanned = 0
        while (scanned < CAMERA_LOOKAHEAD_PACKETS) {
            if (index >= list.size) {
                val segments = source.segments
                do {
                    segment++
                    if (segment >= segments.size) return
                } while (segments[segment].isSnapshot)
                if (segments[segment].startNanos > limit) return
                list = source.packets(segment)
                index = 0
                continue
            }
            val packet = list[index++]
            scanned++
            if (packet.timestampNanos > limit) return
            if (packet.direction != PacketDirection.SERVERBOUND) continue
            if (packet.packetId != RecastInternal.CAMERA_FRAME && packet.packetId != RecastInternal.CAMERA_FRAME_COMPACT) continue
            val frame = PacketCodec.decode(packet) as? CameraFrame ?: continue
            frames.push(CameraSample(packet.timestampNanos, frame.modelView, frame.fov, frame.position, frame.hand))
        }
    }

    private fun loadNextSegment(): List<CapturedPacket>? {
        val segments = source.segments
        while (true) {
            segmentIndex++
            if (segmentIndex >= segments.size) {
                segmentIndex = segments.size
                return null
            }
            if (segments[segmentIndex].isSnapshot) continue
            val packets = source.packets(segmentIndex)
            current = packets
            packetIndex = 0
            return packets
        }
    }

    private fun deliver(packet: CapturedPacket, mode: DeliveryMode) {
        packetsDelivered++
        for (consumer in consumers) {
            try {
                consumer.onPacket(packet, mode)
            } catch (error: Throwable) {
                logger.error("Replay consumer ${consumer::class.simpleName} failed on $packet", error)
            }
        }
    }

    companion object {
        const val MIN_SPEED = 0.05
        const val TICK_NANOS = 50_000_000L
        const val MARKER_SCAN_SEGMENTS = 16
        const val CAMERA_LOOKAHEAD_NANOS = 250_000_000L
        const val CAMERA_LOOKAHEAD_PACKETS = 6000
        const val MARKER_SCAN_NANOS = 5_000_000_000L
        const val MAX_SPEED = 32.0
        const val DIMENSION_BOUNCE_WINDOW_NANOS = 3_000_000_000L

        fun identityOf(source: ReplaySource): RecorderIdentity {
            val uuid = source.header.metadata(RecastFormat.MetadataKeys.PLAYER_UUID)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            return RecorderIdentity(uuid, source.header.metadata(RecastFormat.MetadataKeys.PLAYER_NAME))
        }
    }
}
