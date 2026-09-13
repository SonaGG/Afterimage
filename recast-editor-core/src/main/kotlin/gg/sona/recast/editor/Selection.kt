package gg.sona.recast.editor

import java.util.*

data class Selection(
    val keyframeTimes: Set<Long> = emptySet(),
    val clipIds: Set<UUID> = emptySet(),
    val markerIds: Set<UUID> = emptySet(),
    val valueKeys: Set<ValueKey> = emptySet(),
    val viewTimes: Set<Long> = emptySet(),
    val timelapseIds: Set<UUID> = emptySet(),
    val momentIds: Set<UUID> = emptySet(),
) {
    val isEmpty: Boolean get() = keyframeTimes.isEmpty() && clipIds.isEmpty() && markerIds.isEmpty() && valueKeys.isEmpty() && viewTimes.isEmpty() && timelapseIds.isEmpty() && momentIds.isEmpty()

    val single: Boolean get() = keyframeTimes.size + clipIds.size + markerIds.size + valueKeys.size + viewTimes.size + timelapseIds.size + momentIds.size == 1

    fun withValueKeyframe(lane: ValueLane, nanos: Long, additive: Boolean = false): Selection =
        if (additive) copy(
            valueKeys = valueKeys + ValueKey(
                lane,
                nanos
            )
        ) else Selection(valueKeys = setOf(ValueKey(lane, nanos)))

    fun withViewKeyframe(nanos: Long, additive: Boolean = false): Selection =
        if (additive) copy(viewTimes = viewTimes + nanos) else Selection(viewTimes = setOf(nanos))

    fun withMarker(id: UUID, additive: Boolean = false): Selection =
        if (additive) copy(markerIds = markerIds + id) else Selection(markerIds = setOf(id))

    fun withMoment(id: UUID, additive: Boolean = false): Selection =
        if (additive) copy(momentIds = momentIds + id) else Selection(momentIds = setOf(id))

    fun withTimelapse(id: UUID, additive: Boolean = false): Selection =
        if (additive) copy(timelapseIds = timelapseIds + id) else Selection(timelapseIds = setOf(id))

    fun withKeyframe(nanos: Long, additive: Boolean = false): Selection =
        if (additive) copy(keyframeTimes = keyframeTimes + nanos) else Selection(keyframeTimes = setOf(nanos))

    fun withClip(id: UUID, additive: Boolean = false): Selection =
        if (additive) copy(clipIds = clipIds + id) else Selection(clipIds = setOf(id))

    fun withoutKeyframe(nanos: Long): Selection = copy(keyframeTimes = keyframeTimes - nanos)

    fun valueTimes(lane: ValueLane): Set<Long> = valueKeys.filter { it.lane == lane }.map { it.nanos }.toSet()

    companion object {
        val NONE = Selection()
    }
}
