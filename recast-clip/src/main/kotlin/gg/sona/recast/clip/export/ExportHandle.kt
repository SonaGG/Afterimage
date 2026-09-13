package gg.sona.recast.clip.export

import java.nio.file.Path
import java.util.*
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

class ExportHandle internal constructor(val id: UUID, val job: ExportJob) {
    internal val stateRef = AtomicReference(ExportState.QUEUED)

    @Volatile
    var progress: Double = 0.0
        internal set

    @Volatile
    var result: Path? = null
        internal set

    @Volatile
    var failure: Throwable? = null
        internal set

    @Volatile
    var detail: String = ""
        internal set

    @Volatile
    var startedAtNanos: Long = 0L
        internal set

    @Volatile
    var finishedAtNanos: Long = 0L
        internal set

    @Volatile
    internal var cancelRequested = false

    internal var future: Future<*>? = null

    val state: ExportState get() = stateRef.get()

    fun cancel(): Boolean {
        if (stateRef.compareAndSet(ExportState.QUEUED, ExportState.CANCELLED)) {
            future?.cancel(false)
            return true
        }
        if (state != ExportState.RUNNING) return false
        cancelRequested = true
        detail = "cancelling"
        job.cancel()
        return true
    }
}
