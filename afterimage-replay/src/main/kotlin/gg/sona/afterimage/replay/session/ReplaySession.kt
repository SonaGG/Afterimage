package gg.sona.afterimage.replay.session

import gg.sona.afterimage.core.event.Listeners
import gg.sona.afterimage.core.log.AfterimageLog
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.format.AfterimageFormat
import gg.sona.afterimage.format.RecordingHeader
import gg.sona.afterimage.format.SegmentInfo
import gg.sona.afterimage.format.SegmentKind
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.replay.protocol.ReplayProtocol
import gg.sona.afterimage.replay.protocol.ReplayProtocols
import gg.sona.afterimage.replay.protocol.ReplayState
import gg.sona.afterimage.replay.consumer.DeliveryMode
import gg.sona.afterimage.replay.consumer.ReplayConsumer
import gg.sona.afterimage.replay.consumer.ResetReason
import gg.sona.afterimage.replay.source.ReplaySource
import gg.sona.afterimage.world.GameNames
import gg.sona.afterimage.world.RecorderIdentity
import gg.sona.afterimage.world.WorldState
import java.util.*
import kotlin.math.abs

class ReplaySession(
    val source: ReplaySource,
    consumers: List<ReplayConsumer> = emptyList(),
    private val options: ReplayOptions = ReplayOptions(),
    val protocol: ReplayProtocol = ReplayProtocols.forVersion(source.header.protocolVersion),
    val state: ReplayState = protocol.createState(identityOf(source)),
    private val diffSeeks: Boolean = true,
) : AutoCloseable {

    private val logger = AfterimageLog.logger("afterimage.replay")
    val world: WorldState get() = state.world
    val names: GameNames = GameNames.forProtocol(source.header.protocolVersion)
    private val consumers = ArrayList<ReplayConsumer>().apply {
        add(state)
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
            if (!linear && options.settleNanos > 0L && world.joined && settleTo(target)) return
            if (target >= positionNanos && (linear || !shouldJump(target))) {
                deliverUntil(target, DeliveryMode.SEEK)
                return
            }
            if (diffSeeks && world.joined && seekByDiff(target)) return
            rebuildFromKeyframe(target, ResetReason.SEEK)
            deliverUntil(target, DeliveryMode.SEEK)
        } finally {
            lastSeekDurationNanos = System.nanoTime() - started
            seeks++
        }
    }

    var settling: Boolean = false
        private set

    private fun settleTo(target: Long): Boolean {
        val pre = maxOf(target - options.settleNanos, earliestSeekable())
        if (pre >= target) return false
        settling = true
        val started = System.nanoTime()
        try {
            if (!(diffSeeks && seekByDiff(pre, notifyListeners = false, freshEntities = true))) {
                rebuildFromKeyframe(pre, ResetReason.SEEK)
                deliverUntil(pre, DeliveryMode.SEEK, notifyListeners = false)
            }
            lastPrePositionNanos = System.nanoTime() - started
            val prepared = System.nanoTime()
            listeners.dispatch { it.onSettleStart(pre) }
            lastPrepareNanos = System.nanoTime() - prepared
            deliverUntil(target, DeliveryMode.LIVE, notifyMode = DeliveryMode.SEEK)
        } finally {
            settling = false
        }
        return true
    }

    var lastPrePositionNanos: Long = 0L
        private set

    var lastPrepareNanos: Long = 0L
        private set

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

    private fun seekByDiff(target: Long, notifyListeners: Boolean = true, freshEntities: Boolean = false): Boolean {
        val scratch = scratchSession()
        synchronized(scratch) {
            scratch.seek(target)
            if (!state.canDiff(scratch.state)) return false
            state.dropFutureCameraFrames(minOf(target, positionNanos))
            val diff = state.diff(scratch.state, target, freshEntities)
            lastDiffPackets = diff.size
            for (packet in diff) deliver(packet, DeliveryMode.SEEK)
            segmentIndex = scratch.segmentIndex
            packetIndex = scratch.packetIndex
            current = scratch.current
            positionNanos = target
            if (hasTickMarkers()) worldTickNanos = scratch.worldTickNanos else alignWorldTick(target)
            state.adoptFrom(scratch.state, target)
            primeCameraLookahead(target)
        }
        consumers.forEach { it.onSettled(target, DeliveryMode.SEEK) }
        if (notifyListeners) listeners.dispatch { it.onPositionChanged(target, DeliveryMode.SEEK) }
        return true
    }

    private fun scratchSession(): ReplaySession =
        scratch ?: ReplaySession(
            source,
            emptyList(),
            options.copy(loop = false, settleNanos = 0L),
            protocol,
            state.fork(),
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
                    .firstOrNull { protocol.isJoin(it) }
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
            if (packets.any { protocol.isTickMarker(it) }) {
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

    private fun deliverUntil(target: Long, mode: DeliveryMode, notifyListeners: Boolean = true, notifyMode: DeliveryMode = mode) {
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
                if (protocol.isTickMarker(packet) && packet.timestampNanos > worldTickNanos) fireTick(packet.timestampNanos)
            } else {
                tickUpTo(packet.timestampNanos)
            }
            deliver(packet, mode)
            packetIndex++
        }
        if (!markers) tickUpTo(target)
        positionNanos = target
        primeCameraLookahead(target)
        consumers.forEach { it.onSettled(target, notifyMode) }
        if (notifyListeners) listeners.dispatch { it.onPositionChanged(target, notifyMode) }
    }

    private fun skipDimensionBounce(packets: List<CapturedPacket>): Boolean {
        val leaving = packets[packetIndex]
        val leavingDimension = protocol.respawnDimension(leaving) ?: return false
        val returning = packets.getOrNull(packetIndex + 1) ?: return false
        val returningDimension = protocol.respawnDimension(returning) ?: return false
        if (returning.timestampNanos - leaving.timestampNanos > DIMENSION_BOUNCE_WINDOW_NANOS) return false
        val currentDimension = world.dimension
        if (leavingDimension == currentDimension) return false
        if (returningDimension != currentDimension) return false
        packetIndex += 2
        return true
    }

    fun packetsBetween(fromNanos: Long, toNanos: Long, accept: (CapturedPacket) -> Boolean): List<CapturedPacket> {
        val result = ArrayList<CapturedPacket>()
        for (segment in source.segments) {
            if (segment.isSnapshot || segment.endNanos < fromNanos) continue
            if (segment.startNanos > toNanos) break
            for (packet in source.packets(segment.index)) {
                if (packet.timestampNanos < fromNanos || !accept(packet)) continue
                if (packet.timestampNanos > toNanos) break
                result += packet
            }
        }
        return result
    }

    private fun primeCameraLookahead(target: Long) {
        var list = current ?: return
        var index = packetIndex
        var segment = segmentIndex
        val limit = target + CAMERA_LOOKAHEAD_NANOS
        val frames = world.localPlayer.cameraFrames
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
            val sample = protocol.cameraSample(packet) ?: continue
            frames.push(sample)
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
            val uuid = source.header.metadata(AfterimageFormat.MetadataKeys.PLAYER_UUID)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            return RecorderIdentity(uuid, source.header.metadata(AfterimageFormat.MetadataKeys.PLAYER_NAME))
        }
    }
}
