package gg.sona.afterimage.render.sink

import gg.sona.afterimage.render.ExportSettings
import gg.sona.afterimage.render.RenderedFrame
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class AsyncFrameSink(private val inner: FrameSink, capacity: Int = 3) : FrameSink {

    private val queue = ArrayBlockingQueue<RenderedFrame>(capacity.coerceAtLeast(1))
    private val spare = ArrayBlockingQueue<ByteArray>(capacity.coerceAtLeast(1) + 1)
    private val spareDepth = ArrayBlockingQueue<FloatArray>(capacity.coerceAtLeast(1) + 1)
    private var worker: Thread? = null

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    private var closing = false

    override fun begin(settings: ExportSettings) {
        inner.begin(settings)
        worker = Thread({
            try {
                while (true) {
                    val frame = queue.poll(50, TimeUnit.MILLISECONDS)
                    if (frame == null) {
                        if (closing) break
                        continue
                    }
                    inner.accept(frame)
                    spare.offer(frame.rgba)
                    frame.depth?.let { spareDepth.offer(it) }
                }
            } catch (error: Throwable) {
                failure = error
                queue.clear()
            }
        }, "afterimage-frame-sink").apply { isDaemon = true }.also { it.start() }
    }

    override fun accept(frame: RenderedFrame) {
        failure?.let { throw IllegalStateException("frame sink failed: ${it.message}", it) }
        val rgba = spare.poll()?.takeIf { it.size == frame.rgba.size } ?: ByteArray(frame.rgba.size)
        System.arraycopy(frame.rgba, 0, rgba, 0, rgba.size)
        val depth = frame.depth?.let { source ->
            val copy = spareDepth.poll()?.takeIf { it.size == source.size } ?: FloatArray(source.size)
            System.arraycopy(source, 0, copy, 0, copy.size)
            copy
        }
        val copy = RenderedFrame(frame.index, frame.nanos, frame.width, frame.height, rgba, depth)
        while (!queue.offer(copy, 100, TimeUnit.MILLISECONDS)) {
            failure?.let { throw IllegalStateException("frame sink failed: ${it.message}", it) }
        }
    }

    override fun close() {
        closing = true
        worker?.join()
        inner.close()
        failure?.let { throw IllegalStateException("frame sink failed: ${it.message}", it) }
    }

    override fun abort() {
        closing = true
        queue.clear()
        worker?.join()
        inner.abort()
    }
}
