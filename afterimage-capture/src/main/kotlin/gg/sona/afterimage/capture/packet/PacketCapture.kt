package gg.sona.afterimage.capture.packet

import gg.sona.afterimage.capture.CaptureConfig
import gg.sona.afterimage.capture.CaptureListener
import gg.sona.afterimage.capture.CaptureStats
import gg.sona.afterimage.capture.recording.RecordingInfo
import gg.sona.afterimage.capture.recording.RecordingTarget
import gg.sona.afterimage.capture.state.StateTracker
import gg.sona.afterimage.capture.state.StateTrackerFactory
import gg.sona.afterimage.core.concurrent.DrainThread
import gg.sona.afterimage.core.concurrent.MpscRingBuffer
import gg.sona.afterimage.core.event.Listeners
import gg.sona.afterimage.core.log.AfterimageLog
import gg.sona.afterimage.core.time.Clock
import gg.sona.afterimage.core.time.SessionClock
import gg.sona.afterimage.core.time.SystemClock
import gg.sona.afterimage.format.AfterimageWriter
import gg.sona.afterimage.format.RecordingHeader
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketDirection
import java.nio.file.Files
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

class PacketCapture(
    private val config: CaptureConfig,
    trackerFactory: StateTrackerFactory,
    sourceClock: Clock = SystemClock,
    private val protocolVersion: Int = 47,
) : PacketSink, AutoCloseable {

    private val logger = AfterimageLog.logger("afterimage.capture")
    val clock: SessionClock = SessionClock(sourceClock)
    val observers = Listeners<PacketObserver>()
    val listeners = Listeners<CaptureListener>()

    private val ring = MpscRingBuffer<CapturedPacket>(config.ringCapacity)
    private val overflow = ConcurrentLinkedQueue<CapturedPacket>()
    private val commands = ConcurrentLinkedQueue<() -> Unit>()

    private val reorderBuffer = PriorityQueue<CapturedPacket>(256, compareBy { it.timestampNanos })
    private val tracker: StateTracker = trackerFactory.create()
    private val packetsCaptured = AtomicLong()
    private val overflowEvents = AtomicLong()

    @Volatile
    private var overflowing = false

    @Volatile
    private var closed = false

    @Volatile
    private var active: ActiveRecording? = null

    private val thread = DrainThread(config.threadName, drain = ::drainOnce, idle = ::idle, finish = ::finish).start()

    private inner class ActiveRecording(val writer: AfterimageWriter, val originNanos: Long) {
        var keyframes = 0
        var joinedKeyframe = false
        var lastKeyframeNanos = 0L
        var recorded = 0L

        fun recordingNanos(captureNanos: Long): Long = maxOf(0L, captureNanos - originNanos)
    }

    val isRecording: Boolean get() = active != null

    val currentRecordingPath get() = active?.writer?.path

    fun recordingInfo(): RecordingInfo? {
        val recording = active ?: return null
        return RecordingInfo(recording.writer.path, recording.writer.header.sessionId, recording.originNanos)
    }

    override fun offer(direction: PacketDirection, packetId: Int, payload: ByteArray) {
        if (closed) return
        val packet = CapturedPacket(direction, clock.nanos(), packetId, payload)
        packetsCaptured.incrementAndGet()
        if (overflowing || !ring.offer(packet)) {
            if (!overflowing) {
                overflowing = true
                overflowEvents.incrementAndGet()
            }
            overflow.add(packet)
        }
        thread.wake()
    }

    fun startRecording(target: RecordingTarget): CountDownLatch {
        val started = CountDownLatch(1)
        submit {
            try {
                beginRecording(target)
            } finally {
                started.countDown()
            }
        }
        return started
    }

    fun stopRecording(): CountDownLatch {
        val stopped = CountDownLatch(1)
        submit {
            try {
                endRecording()
            } finally {
                stopped.countDown()
            }
        }
        return stopped
    }

    fun mark(packet: CapturedPacket) {
        submit { record(packet.withTimestamp(clock.nanos())) }
    }

    fun stats(): CaptureStats {
        val recording = active
        return CaptureStats(
            packetsCaptured = packetsCaptured.get(),
            packetsRecorded = recording?.recorded ?: 0L,
            bytesWritten = recording?.writer?.bytesWritten ?: 0L,
            keyframes = recording?.keyframes ?: 0,
            overflowEvents = overflowEvents.get(),
            elapsedNanos = recording?.let { clock.nanos() - it.originNanos } ?: 0L,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        submit { endRecording() }
        thread.close()
    }

    private fun submit(command: () -> Unit) {
        commands.add(command)
        thread.wake()
    }

    private fun drainOnce(): Boolean {
        var drained = 0
        var ringEmpty = false
        while (drained < DRAIN_BATCH) {
            val packet = ring.poll()
            if (packet == null) {
                ringEmpty = true
                break
            }
            reorderBuffer.add(packet)
            drained++
        }
        if (ringEmpty && overflowing) {
            while (true) {
                val packet = overflow.poll() ?: break
                reorderBuffer.add(packet)
                drained++
            }
            if (overflow.isEmpty()) overflowing = false
        }
        var progressed = drained > 0
        if (flushReady(clock.nanos())) progressed = true
        if (ringEmpty && reorderBuffer.isEmpty()) {
            while (true) {
                val command = commands.poll() ?: break
                runTask("command") { command() }
                progressed = true
            }
        }
        return progressed
    }

    private fun flushReady(now: Long): Boolean {
        var flushed = false
        while (true) {
            val head = reorderBuffer.peek() ?: break
            if (now - head.timestampNanos < config.reorderWindowNanos) break
            process(reorderBuffer.poll()!!)
            flushed = true
        }
        return flushed
    }

    private fun flushAll() {
        while (true) {
            process(reorderBuffer.poll() ?: break)
        }
    }

    private fun process(packet: CapturedPacket) {
        runTask("tracker") { tracker.observe(packet) }
        if (!observers.isEmpty) {
            observers.dispatch { observer -> runTask("observer") { observer.onPacket(packet) } }
        }
        record(packet)
    }

    private fun record(packet: CapturedPacket) {
        val recording = active ?: return
        runTask("writer") {
            val nanos = recording.recordingNanos(packet.timestampNanos)
            recording.writer.append(packet.withTimestamp(nanos))
            recording.recorded++
            if (!recording.joinedKeyframe) {
                if (writeKeyframe(recording, nanos)) recording.joinedKeyframe = true
            } else if (nanos - recording.lastKeyframeNanos >= config.keyframeIntervalNanos) {
                writeKeyframe(recording, nanos)
            }
        }
    }

    private fun writeKeyframe(recording: ActiveRecording, nanos: Long): Boolean {
        val packets = tracker.snapshot(nanos + recording.originNanos)
        if (packets.isEmpty()) return false
        recording.writer.writeSnapshot(nanos, packets)
        recording.lastKeyframeNanos = nanos
        recording.keyframes++
        listeners.dispatch { it.onKeyframe(nanos, packets.size) }
        return true
    }

    private fun beginRecording(target: RecordingTarget) {
        endRecording()
        Files.createDirectories(target.path.parent)
        val header = RecordingHeader(
            sessionId = UUID.randomUUID(),
            protocolVersion = protocolVersion,
            startEpochMillis = System.currentTimeMillis(),
            keyframeIntervalNanos = config.keyframeIntervalNanos,
            metadata = target.metadata,
        )
        val writer = AfterimageWriter(target.path, header, config.writerOptions)
        val recording = ActiveRecording(writer, clock.nanos())
        active = recording
        recording.joinedKeyframe = writeKeyframe(recording, 0L)
        listeners.dispatch { it.onRecordingStarted(target.path) }
        logger.info("Recording to ${target.path.fileName}")
    }

    private fun endRecording() {
        val recording = active ?: return
        active = null
        val stats = stats().copy(
            packetsRecorded = recording.recorded,
            bytesWritten = recording.writer.bytesWritten,
            keyframes = recording.keyframes,
            elapsedNanos = clock.nanos() - recording.originNanos,
        )
        runTask("close") { recording.writer.close() }
        listeners.dispatch { it.onRecordingStopped(recording.writer.path, stats) }
        logger.info("Stopped recording ${recording.writer.path.fileName}: ${stats.packetsRecorded} packets, ${stats.bytesWritten} bytes, ${stats.keyframes} keyframes")
    }

    private fun idle() {
        val recording = active ?: return
        runTask("sync") {
            recording.writer.flushIfStale(recording.recordingNanos(clock.nanos()))
            recording.writer.sync()
        }
    }

    private fun finish() {
        flushAll()
        endRecording()
    }

    private inline fun runTask(stage: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            logger.error("Capture $stage failed", error)
            listeners.dispatch { it.onFailure(stage, error) }
        }
    }

    private companion object {
        const val DRAIN_BATCH = 1024
    }
}
