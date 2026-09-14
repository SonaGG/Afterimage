package gg.sona.recast.clip.export

import gg.sona.recast.core.concurrent.NamedThreadFactory
import gg.sona.recast.core.event.Listeners
import gg.sona.recast.core.log.RecastLog
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExportQueue(parallelism: Int = 1) : AutoCloseable {
    private val logger = RecastLog.logger("recast.export")
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(parallelism.coerceAtLeast(1), NamedThreadFactory("recast-export"))
    private val handles = ConcurrentHashMap<UUID, ExportHandle>()
    val listeners = Listeners<ExportListener>()

    fun submit(job: ExportJob): ExportHandle {
        val handle = ExportHandle(UUID.randomUUID(), job)
        handles[handle.id] = handle
        handle.future = executor.submit { execute(handle) }
        listeners.dispatch { it.onStateChanged(handle) }
        return handle
    }

    fun handles(): List<ExportHandle> = handles.values.toList()

    fun forget(id: UUID) {
        handles.remove(id)
    }

    private fun execute(handle: ExportHandle) {
        if (!handle.stateRef.compareAndSet(ExportState.QUEUED, ExportState.RUNNING)) return
        handle.startedAtNanos = System.nanoTime()
        listeners.dispatch { it.onStateChanged(handle) }
        try {
            val report = ExportProgress(
                { value ->
                    handle.progress = value
                    listeners.dispatch { it.onProgress(handle) }
                },
                { text -> handle.detail = text },
                { message -> handle.warn(message) },
            )
            handle.result = handle.job.run(report)
            if (handle.cancelRequested) {
                handle.stateRef.set(ExportState.CANCELLED)
            } else {
                handle.progress = 1.0
                handle.stateRef.set(ExportState.DONE)
            }
        } catch (error: Throwable) {
            if (handle.cancelRequested) {
                handle.stateRef.set(ExportState.CANCELLED)
            } else {
                handle.failure = error
                handle.stateRef.set(ExportState.FAILED)
                logger.error("Export ${handle.job.name} failed", error)
            }
        }
        handle.finishedAtNanos = System.nanoTime()
        listeners.dispatch { it.onStateChanged(handle) }
    }

    override fun close() {
        executor.shutdown()
        executor.awaitTermination(30, TimeUnit.SECONDS)
    }
}
