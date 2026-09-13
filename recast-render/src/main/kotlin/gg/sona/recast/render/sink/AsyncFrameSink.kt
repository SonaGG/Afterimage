package gg.sona.recast.render.sink

import gg.sona.recast.render.ExportSettings
import gg.sona.recast.render.RenderedFrame
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class AsyncFrameSink(private val inner: FrameSink, capacity: Int = 3) : FrameSink {

    private val queue = ArrayBlockingQueue<RenderedFrame>(capacity.coerceAtLeast(1))
    private var worker: Thread? = null

    private var failure: Throwable? = null

    private var closing = false

    override fun begin(settings: ExportSettings) {
        inner.begin(settings)
        worker = Thread({
            try {
                while (true) {
                    val frame = queue.poll(50, TimeUnit.MILLISECONDS)
                    if (frame != null) inner.accept(frame) else if (closing) break
                }
            } catch (error: Throwable) {
                failure = error
                queue.clear()
            }
        }, "recast-frame-sink").apply { isDaemon = true }.also { it.start() }
    }

    override fun accept(frame: RenderedFrame) {
        failure?.let { throw IllegalStateException("frame sink failed: ${it.message}", it) }
        val copy = RenderedFrame(
            frame.index,
            frame.nanos,
            frame.width,
            frame.height,
            frame.rgba.copyOf(),
            frame.depth?.copyOf()
        )
        while (!queue.offer(
                copy,
                100,
                TimeUnit.MILLISECONDS
            )
        ) failure?.let { throw IllegalStateException("frame sink failed: ${it.message}", it) }
    }

    override fun close() {
        closing = true
        worker?.join()
        inner.close()
        failure?.let { throw IllegalStateException("frame sink failed: ${it.message}", it) }
    }
}
