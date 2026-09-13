package gg.sona.recast.core.concurrent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

class DrainThread(
    name: String,
    private val parkNanos: Long = 1_000_000L,
    private val drain: () -> Boolean,
    private val idle: () -> Unit = {},
    private val finish: () -> Unit = {},
) : AutoCloseable {

    private val running = AtomicBoolean(true)
    private val stopped = CountDownLatch(1)

    @Volatile
    private var parked = false

    private val thread = Thread(::loop, name).apply { isDaemon = true }

    fun start(): DrainThread {
        thread.start()
        return this
    }

    fun wake() {
        if (parked) LockSupport.unpark(thread)
    }

    private fun loop() {
        try {
            while (running.get()) {
                if (drain()) continue
                idle()
                parked = true
                if (running.get()) LockSupport.parkNanos(this, parkNanos)
                parked = false
            }
            while (drain()) {
                continue
            }
        } finally {
            try {
                finish()
            } finally {
                stopped.countDown()
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        LockSupport.unpark(thread)
        stopped.await(30, TimeUnit.SECONDS)
    }
}
