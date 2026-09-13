package gg.sona.recast.editor

import gg.sona.recast.index.IndexEventKind
import gg.sona.recast.index.IndexStore
import gg.sona.recast.index.ReplayIndex
import gg.sona.recast.index.query.EventLabels
import gg.sona.recast.index.query.ReplaySearch
import gg.sona.recast.replay.source.ReplaySource
import gg.sona.recast.replay.state.shadow.RecorderIdentity
import java.nio.file.Path

class EventIndex {
    private val store = IndexStore()

    @Volatile
    var index: ReplayIndex? = null
        private set

    @Volatile
    var complete: Boolean = false
        private set

    @Volatile
    var events: List<TimelineEvent> = emptyList()
        private set

    @Volatile
    var search: ReplaySearch? = null
        private set

    @Volatile
    var moments: List<Moment> = emptyList()
        private set

    val progress: Double get() = if (complete) 1.0 else store.progress

    fun cancel() = store.cancel()

    fun build(recording: Path, source: ReplaySource, identity: RecorderIdentity) {
        val built = store.load(recording, source, identity) ?: return
        index = built
        events = timelineEvents(built)
        search = ReplaySearch(built)
        moments = runCatching { MomentDetector(built).detect() }.getOrDefault(emptyList())
        complete = true
    }

    private fun timelineEvents(index: ReplayIndex): List<TimelineEvent> {
        val labels = EventLabels(index)
        val table = index.events
        val recorderIds = index.recorder.map { it.entityId }.toSet()
        val result = ArrayList<TimelineEvent>()
        for (i in 0 until table.size) {
            val kind = when (table.kindAt(i)) {
                IndexEventKind.KILL -> if (table.a[i] in recorderIds) EventKind.KILL else continue
                IndexEventKind.DEATH -> if (table.a[i] in recorderIds) EventKind.DEATH else continue
                IndexEventKind.TITLE -> EventKind.TITLE
                IndexEventKind.RESPAWN -> EventKind.RESPAWN
                IndexEventKind.MARKER -> EventKind.MARKER
                IndexEventKind.BOSS -> EventKind.BOSS
                IndexEventKind.EXPLOSION -> EventKind.EXPLOSION
                else -> continue
            }
            result += TimelineEvent(table.nanos[i], kind, labels.label(i))
        }
        return result
    }

}
