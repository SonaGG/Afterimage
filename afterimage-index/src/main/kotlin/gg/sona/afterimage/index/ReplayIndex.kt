package gg.sona.afterimage.index

import gg.sona.afterimage.core.time.Nanos
import java.util.*

class ReplayIndex(
    val startNanos: Long,
    val endNanos: Long,
    val tickNanos: Long,
    val tickCount: Int,
    val tracks: List<EntityTrack>,
    val events: EventTable,
    val blocks: BlockChangeTable,
    val dimensions: IntArray,
) {
    val tickSeconds: Double = tickNanos / Nanos.PER_SECOND.toDouble()

    private val byId: Map<Int, List<EntityTrack>> = tracks.groupBy { it.entityId }
    private val byUuid: Map<UUID, List<EntityTrack>> = tracks.filter { it.uuid != null }.groupBy { it.uuid!! }
    private val byName: Map<String, List<EntityTrack>> =
        tracks.filter { it.name != null }.groupBy { it.name!!.lowercase() }

    val players: List<EntityTrack> get() = tracks.filter { it.isPlayer }

    val recorder: List<EntityTrack> get() = tracks.filter { it.isRecorder }

    val playerNames: List<String>
        get() {
            val names = LinkedHashSet<String>()
            for (track in tracks) if (track.isRecorder && track.name != null && isUsername(track.name)) names += track.name
            for (track in tracks) if (track.isPlayer && track.name != null && isUsername(track.name)) names += track.name
            return names.toList()
        }

    fun tickOf(nanos: Long): Int = ((nanos - startNanos) / tickNanos).toInt().coerceIn(0, maxOf(0, tickCount - 1))

    fun nanosOf(tick: Int): Long = startNanos + tick * tickNanos

    fun tracksOf(entityId: Int): List<EntityTrack> = byId[entityId] ?: emptyList()

    fun trackAt(entityId: Int, tick: Int): EntityTrack? = byId[entityId]?.firstOrNull { it.covers(tick) }

    fun tracksAt(tick: Int): List<EntityTrack> = tracks.filter { it.covers(tick) }

    fun byUuid(uuid: UUID): List<EntityTrack> = byUuid[uuid] ?: emptyList()

    fun resolve(reference: String): List<EntityTrack> {
        val text = reference.trim()
        if (text.isEmpty()) return emptyList()
        if (text.equals("me", true) || text.equals("recorder", true)) return recorder
        if (text.startsWith("#")) return text.substring(1).toIntOrNull()?.let { tracksOf(it) } ?: emptyList()
        byName[text.lowercase()]?.let { return it }
        runCatching { UUID.fromString(text) }.getOrNull()?.let { return byUuid(it) }
        val prefix = byName.entries.filter { it.key.startsWith(text.lowercase()) }
        return if (prefix.size == 1) prefix[0].value else emptyList()
    }

    fun nameOf(entityId: Int, tick: Int): String =
        trackAt(entityId, tick)?.label ?: tracksOf(entityId).firstOrNull()?.label ?: "#$entityId"

    fun dimensionAt(tick: Int): Int = if (dimensions.isEmpty()) 0 else dimensions[tick.coerceIn(0, dimensions.size - 1)]

    companion object {
        const val VERSION = 4

        fun isUsername(name: String): Boolean =
            name.length in 1..16 && name.all { it.isLetterOrDigit() || it == '_' }
    }
}
