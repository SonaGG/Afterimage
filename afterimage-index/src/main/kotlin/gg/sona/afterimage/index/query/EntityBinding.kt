package gg.sona.afterimage.index.query

import gg.sona.afterimage.index.EntityTrack

class EntityBinding(val name: String, val tracks: List<EntityTrack>) {
    private var last: EntityTrack? = null

    val entityIds: Set<Int> = tracks.map { it.entityId }.toSet()

    fun at(tick: Int): EntityTrack? {
        val cached = last
        if (cached != null && cached.covers(tick)) return cached
        for (track in tracks) if (track.covers(tick)) {
            last = track
            return track
        }
        return null
    }

    fun matches(id: Int, tick: Int): Boolean {
        if (id !in entityIds) return false
        for (track in tracks) {
            if (track.entityId != id) continue
            if (tick >= track.firstTick - 2 && tick <= track.lastTick + 2) return true
        }
        return false
    }
}
