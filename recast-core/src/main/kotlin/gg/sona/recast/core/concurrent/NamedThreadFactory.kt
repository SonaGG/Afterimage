package gg.sona.recast.core.concurrent

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

class NamedThreadFactory(private val prefix: String, private val daemon: Boolean = true) : ThreadFactory {
    private val counter = AtomicInteger()

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = daemon }
}
