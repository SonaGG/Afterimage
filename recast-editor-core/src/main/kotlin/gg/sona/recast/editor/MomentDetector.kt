package gg.sona.recast.editor

import gg.sona.recast.core.time.Nanos
import gg.sona.recast.index.EntityTrack
import gg.sona.recast.index.IndexEventKind
import gg.sona.recast.index.ReplayIndex
import gg.sona.recast.index.query.EventLabels
import java.util.*

class MomentDetector(private val index: ReplayIndex) {

    private val labels = EventLabels(index)
    private val recorderIds = index.recorder.map { it.entityId }.toSet()

    fun detect(): List<Moment> {
        val result = ArrayList<Moment>()
        val events = index.events
        val kills = ArrayList<Int>()
        for (i in 0 until events.size) {
            val kind = events.kindAt(i)
            val nanos = events.nanos[i]
            val tick = events.tick[i]
            when (kind) {
                IndexEventKind.KILL -> if (events.a[i] in recorderIds) {
                    kills += i
                    val lowHealth = lowestRecorderHealth(tick - 10 * TICKS_PER_SECOND, tick)
                    val clutch = !lowHealth.isNaN() && lowHealth <= CLUTCH_HEALTH
                    val heat = hurtInvolvingRecorder(nanos - Nanos.ofSeconds(8), nanos)
                    result += moment(
                        nanos - Nanos.ofSeconds(4), nanos + Nanos.ofSeconds(2), nanos,
                        if (clutch) "Clutch on ${
                            labels.name(
                                events.b[i],
                                tick
                            )
                        }" else "Kill on ${labels.name(events.b[i], tick)}",
                        if (clutch) MomentKind.CLUTCH else MomentKind.KILL,
                        if (clutch) 0.95 else (0.6 + 0.04 * heat).coerceAtMost(0.85),
                        events.b[i]
                    )
                }

                IndexEventKind.DEATH -> if (events.a[i] in recorderIds) result += moment(
                    nanos - Nanos.ofSeconds(5), nanos + Nanos.ofSeconds(1), nanos,
                    if (events.b[i] >= 0) "Died to ${labels.name(events.b[i], tick)}" else "Died",
                    MomentKind.DEATH, 0.4, events.b[i]
                )

                IndexEventKind.EXPLOSION -> {
                    val distance = distanceToRecorder(tick, events.x[i], events.y[i], events.z[i])
                    if (events.value[i] >= 3f && distance <= 4.0) result += moment(
                        nanos - Nanos.ofSeconds(2), nanos + Nanos.ofSeconds(2), nanos,
                        "Explosion ${"%.1f".format(distance)} m away", MomentKind.EXPLOSION, 0.35, -1
                    )
                }

                IndexEventKind.TITLE -> {
                    val text = events.text[i]?.uppercase() ?: ""
                    if (VICTORY_WORDS.any { it in text }) result += moment(
                        nanos - Nanos.ofSeconds(8), nanos + Nanos.ofSeconds(3), nanos,
                        events.text[i] ?: "Victory", MomentKind.VICTORY, 0.8, -1
                    )
                }

                else -> Unit
            }
        }
        result += multiKills(kills)
        result += fights()
        result += escapes()
        return merge(result)
    }

    private fun multiKills(kills: List<Int>): List<Moment> {
        val events = index.events
        val result = ArrayList<Moment>()
        var start = 0
        while (start < kills.size) {
            var end = start
            while (end + 1 < kills.size && events.nanos[kills[end + 1]] - events.nanos[kills[end]] <= MULTI_KILL_GAP) end++
            val count = end - start + 1
            if (count >= 2) {
                val first = events.nanos[kills[start]]
                val last = events.nanos[kills[end]]
                result += moment(
                    first - Nanos.ofSeconds(4), last + Nanos.ofSeconds(2), last,
                    when (count) {
                        2 -> "Double kill"
                        3 -> "Triple kill"
                        4 -> "Quad kill"
                        else -> "$count kills"
                    },
                    MomentKind.MULTI_KILL, (0.85 + 0.05 * count).coerceAtMost(1.0), events.b[kills[end]]
                )
            }
            start = end + 1
        }
        return result
    }

    private fun fights(): List<Moment> {
        val events = index.events
        val hurts = ArrayList<Int>()
        for (i in 0 until events.size) {
            if (events.kindAt(i) != IndexEventKind.HURT) continue
            if (events.a[i] in recorderIds || events.b[i] in recorderIds) hurts += i
        }
        val result = ArrayList<Moment>()
        var start = 0
        while (start < hurts.size) {
            var end = start
            while (end + 1 < hurts.size && events.nanos[hurts[end + 1]] - events.nanos[hurts[end]] <= FIGHT_GAP) end++
            val count = end - start + 1
            if (count >= 6) {
                val first = events.nanos[hurts[start]]
                val last = events.nanos[hurts[end]]
                val hasKill = (0 until events.size).any {
                    events.kindAt(it) == IndexEventKind.KILL && events.a[it] in recorderIds && events.nanos[it] in first..last + Nanos.ofSeconds(
                        3
                    )
                }
                if (!hasKill) {
                    val opponent = hurts.subList(start, end + 1)
                        .map { if (events.a[it] in recorderIds) events.b[it] else events.a[it] }
                        .filter { it >= 0 }
                        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: -1
                    result += moment(
                        first - Nanos.ofSeconds(2), last + Nanos.ofSeconds(2), first + (last - first) / 2,
                        if (opponent >= 0) "Fight with ${
                            labels.name(
                                opponent,
                                events.tick[hurts[start]]
                            )
                        }" else "Fight",
                        MomentKind.HIGHLIGHT, (0.4 + 0.02 * count).coerceAtMost(0.7), opponent
                    )
                }
            }
            start = end + 1
        }
        return result
    }

    private fun escapes(): List<Moment> {
        val result = ArrayList<Moment>()
        for (track in index.recorder) {
            var lowTick = -1
            var lowest = Float.MAX_VALUE
            for (i in 0 until track.length) {
                val health = track.health[i]
                if (health.isNaN()) continue
                if (health <= CLUTCH_HEALTH) {
                    if (lowTick < 0 || health < lowest) {
                        if (lowTick < 0) lowTick = i
                        lowest = health
                    }
                } else if (lowTick >= 0 && health >= RECOVERED_HEALTH) {
                    val previous = if (i > 0) track.health[i - 1] else health
                    val respawned = previous.isNaN() || health - previous >= RESPAWN_JUMP || recorderDiedBetween(
                        index.nanosOf(track.firstTick + lowTick),
                        index.nanosOf(track.firstTick + i)
                    )
                    if (!respawned && i - lowTick <= 15 * TICKS_PER_SECOND && hurtInvolvingRecorder(
                            index.nanosOf(track.firstTick + lowTick) - Nanos.ofSeconds(6),
                            index.nanosOf(track.firstTick + lowTick)
                        ) > 0
                    ) {
                        val lowNanos = index.nanosOf(track.firstTick + lowTick)
                        result += moment(
                            lowNanos - Nanos.ofSeconds(5), index.nanosOf(track.firstTick + i), lowNanos,
                            "Escaped at ${"%.1f".format(lowest / 2f)} hearts", MomentKind.ESCAPE, 0.5, track.entityId
                        )
                    }
                    lowTick = -1
                    lowest = Float.MAX_VALUE
                }
            }
        }
        return result
    }

    private fun recorderDiedBetween(fromNanos: Long, toNanos: Long): Boolean {
        val events = index.events
        var i = events.lowerBound(fromNanos)
        while (i < events.size && events.nanos[i] <= toNanos) {
            val kind = events.kindAt(i)
            if ((kind == IndexEventKind.DEATH || kind == IndexEventKind.RESPAWN) && events.a[i] in recorderIds) return true
            i++
        }
        return false
    }

    private fun lowestRecorderHealth(fromTick: Int, toTick: Int): Float {
        var lowest = Float.NaN
        for (track in index.recorder) {
            val start = maxOf(track.firstTick, fromTick)
            val end = minOf(track.lastTick, toTick)
            var tick = start
            while (tick <= end) {
                val health = track.health[track.index(tick)]
                if (!health.isNaN() && (lowest.isNaN() || health < lowest)) lowest = health
                tick++
            }
        }
        return lowest
    }

    private fun hurtInvolvingRecorder(fromNanos: Long, toNanos: Long): Int {
        val events = index.events
        var count = 0
        var i = events.lowerBound(fromNanos)
        while (i < events.size && events.nanos[i] <= toNanos) {
            if (events.kindAt(i) == IndexEventKind.HURT && (events.a[i] in recorderIds || events.b[i] in recorderIds)) count++
            i++
        }
        return count
    }

    private fun distanceToRecorder(tick: Int, x: Double, y: Double, z: Double): Double {
        val track = recorderAt(tick) ?: return Double.MAX_VALUE
        val i = track.index(tick)
        val dx = track.x[i] - x
        val dy = track.y[i] - y
        val dz = track.z[i] - z
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun recorderAt(tick: Int): EntityTrack? = index.recorder.firstOrNull { it.covers(tick) }

    private fun moment(
        start: Long,
        end: Long,
        peak: Long,
        label: String,
        kind: MomentKind,
        score: Double,
        entityId: Int
    ): Moment =
        Moment(
            UUID.nameUUIDFromBytes("$kind:$peak:$label".toByteArray()),
            start.coerceAtLeast(index.startNanos),
            end.coerceAtMost(index.endNanos),
            peak,
            label,
            kind,
            score.coerceIn(0.0, 1.0),
            MomentOrigin.DETECTED,
            entityId
        )

    private fun merge(moments: List<Moment>): List<Moment> {
        val multi = moments.filter { it.kind == MomentKind.MULTI_KILL }
        return moments.filter { moment ->
            !(moment.kind == MomentKind.KILL || moment.kind == MomentKind.CLUTCH) || multi.none { it.contains(moment.peakNanos) }
        }.sortedBy { it.nanos }
    }

    private companion object {
        const val TICKS_PER_SECOND = 20
        const val CLUTCH_HEALTH = 6f
        const val RECOVERED_HEALTH = 14f
        const val RESPAWN_JUMP = 10f
        val MULTI_KILL_GAP = Nanos.ofSeconds(8)
        val FIGHT_GAP = Nanos.ofSeconds(6)
        val VICTORY_WORDS = listOf("VICTORY", "WINNER", "YOU WIN", "YOU WON", "CHAMPION", "FIRST PLACE", "#1")
    }
}
