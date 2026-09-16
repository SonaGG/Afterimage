package gg.sona.afterimage.index.query

import gg.sona.afterimage.index.IndexEventKind
import gg.sona.afterimage.index.ReplayIndex
import kotlin.math.sqrt

class ReplaySearch(private val index: ReplayIndex) {

    private val labels = EventLabels(index)

    fun run(text: String, limit: Int = DEFAULT_LIMIT): SearchResult {
        val query = text.trim()
        if (query.isEmpty()) return SearchResult(query, emptyList(), null, -1, 0L, false)
        val started = System.nanoTime()
        return try {
            val parsed = Parser(query).parse()
            val compiler = Compiler(index)
            val hits = when (parsed) {
                is Query.Events -> if (EventKindSpec.BLOCK in parsed.kinds) blocks(parsed, compiler, limit + 1)
                else events(parsed, compiler, limit + 1)

                is Query.Condition -> spans(parsed, compiler, query, limit + 1)
            }
            val truncated = hits.size > limit
            SearchResult(
                query,
                if (truncated) hits.subList(0, limit) else hits,
                null,
                -1,
                (System.nanoTime() - started) / 1_000_000,
                truncated
            )
        } catch (error: QueryException) {
            SearchResult.failure(query, error.message ?: "Bad query", error.position)
        }
    }

    private class Filters(
        val subject: EntityBinding?,
        val target: EntityBinding?,
        val either: EntityBinding?,
        val texts: List<String>,
        val nearEntity: EntityBinding?,
        val nearPoint: DoubleArray?,
        val radius: Double,
        val afterNanos: Long,
        val beforeNanos: Long,
        val min: Double,
        val max: Double,
        val kinds: Set<IndexEventKind>?,
    )

    private fun filters(query: Query.Events, compiler: Compiler): Filters {
        var subject: EntityBinding? = null
        var target: EntityBinding? = null
        var either: EntityBinding? = null
        val texts = ArrayList<String>()
        var nearEntity: EntityBinding? = null
        var nearPoint: DoubleArray? = null
        var radius = DEFAULT_RADIUS
        var after = Long.MIN_VALUE
        var before = Long.MAX_VALUE
        var min = Double.NEGATIVE_INFINITY
        var max = Double.POSITIVE_INFINITY
        var kinds: MutableSet<IndexEventKind>? = null
        for (filter in query.filters) {
            val numbers = filter.numbers
            when (filter.key) {
                "by", "attacker", "killer", "shooter", "who" -> subject = compiler.entity(filter.text, filter.position)
                "on", "victim", "target", "against" -> target = compiler.entity(filter.text, filter.position)
                "player", "with", "involving", "of" -> {
                    if (numbers != null && numbers.size == 3) nearPoint = numbers
                    else either = compiler.entity(filter.text, filter.position)
                }

                "from" -> if (numbers != null && numbers.size == 1) after = nanosOf(numbers[0]) else subject =
                    compiler.entity(filter.text, filter.position)

                "to" -> if (numbers != null && numbers.size == 1) before = nanosOf(numbers[0]) else target =
                    compiler.entity(filter.text, filter.position)

                "after", "since" -> after = nanosOf(number(filter))
                "before", "until" -> before = nanosOf(number(filter))
                "text", "contains", "says", "item", "name", "sound", "message" -> texts += filter.text.lowercase()
                "near", "at" -> {
                    if (numbers != null && numbers.size == 3) nearPoint = numbers
                    else nearEntity = compiler.entity(filter.text, filter.position)
                }

                "within", "radius", "range" -> radius = number(filter)
                "min", "over", "above" -> min = number(filter)
                "max", "under", "below" -> max = number(filter)
                "kind", "type" -> {
                    val spec = EventKindSpec.of(filter.text)
                        ?: throw QueryException("Unknown event kind '${filter.text}'", filter.position)
                    (kinds ?: HashSet<IndexEventKind>().also { kinds = it }).addAll(spec.kinds)
                }

                else -> throw QueryException("Unknown filter '${filter.key}:'", filter.position)
            }
        }
        return Filters(subject, target, either, texts, nearEntity, nearPoint, radius, after, before, min, max, kinds)
    }

    private fun number(filter: EventFilter): Double =
        filter.numbers?.takeIf { it.size == 1 }?.get(0)
            ?: filter.text.toDoubleOrNull()
            ?: throw QueryException("'${filter.key}:' needs a number", filter.position)

    private fun nanosOf(seconds: Double): Long = index.startNanos + (seconds * 1_000_000_000L).toLong()

    private fun events(query: Query.Events, compiler: Compiler, limit: Int): List<SearchHit> {
        val filters = filters(query, compiler)
        val kinds = HashSet<IndexEventKind>()
        for (spec in query.kinds) kinds += spec.kinds
        filters.kinds?.let { kinds.retainAll(it) }
        val condition = query.condition?.let { compiler.compile(it) }
        val events = index.events
        val hits = ArrayList<SearchHit>()
        val start = if (filters.afterNanos == Long.MIN_VALUE) 0 else events.lowerBound(filters.afterNanos)
        val lastTick = maxOf(0, index.tickCount - 1)
        for (i in start until events.size) {
            val nanos = events.nanos[i]
            if (nanos > filters.beforeNanos) break
            if (events.kindAt(i) !in kinds) continue
            val tick = events.tick[i].coerceAtMost(lastTick)
            val kind = events.kindAt(i)
            val actor = if (kind.actorIsB) events.b[i] else events.a[i]
            val target = if (kind.actorIsB) events.a[i] else events.b[i]
            if (filters.subject != null && !filters.subject.matches(actor, tick)) continue
            if (filters.target != null && !filters.target.matches(target, tick)) continue
            if (filters.either != null && !filters.either.matches(actor, tick) && !filters.either.matches(
                    target,
                    tick
                )
            ) continue
            val value = events.value[i].toDouble()
            val bounded = filters.min != Double.NEGATIVE_INFINITY || filters.max != Double.POSITIVE_INFINITY
            if (bounded && (value.isNaN() || value < filters.min || value > filters.max)) continue
            if (filters.texts.isNotEmpty()) {
                val text = events.text[i]?.lowercase() ?: ""
                val label = labels.label(i).lowercase()
                if (!filters.texts.all { text.contains(it) || label.contains(it) }) continue
            }
            if (filters.nearEntity != null || filters.nearPoint != null) {
                val position = eventPosition(i, tick) ?: continue
                val point = filters.nearPoint ?: filters.nearEntity!!.at(tick)?.let { track ->
                    val j = track.index(tick)
                    doubleArrayOf(track.x[j], track.y[j], track.z[j])
                } ?: continue
                if (distance(position, point) > filters.radius) continue
            }
            if (condition != null && !condition.eval(tick).truthy) continue
            hits += SearchHit(
                nanos, nanos, labels.label(i), kind.label, kind,
                if (events.a[i] >= 0) events.a[i] else events.b[i], events.x[i], events.y[i], events.z[i]
            )
            if (hits.size >= limit) break
        }
        return hits
    }

    private fun eventPosition(i: Int, tick: Int): DoubleArray? {
        val events = index.events
        if (events.hasPosition(i)) return doubleArrayOf(events.x[i], events.y[i], events.z[i])
        val id = if (events.a[i] >= 0) events.a[i] else events.b[i]
        if (id < 0) return null
        val track = index.trackAt(id, tick) ?: return null
        val j = track.index(tick)
        return doubleArrayOf(track.x[j], track.y[j], track.z[j])
    }

    private fun blocks(query: Query.Events, compiler: Compiler, limit: Int): List<SearchHit> {
        val filters = filters(query, compiler)
        val condition = query.condition?.let { compiler.compile(it) }
        val blocks = index.blocks
        val hits = ArrayList<SearchHit>()
        val start = if (filters.afterNanos == Long.MIN_VALUE) 0 else blocks.lowerBound(filters.afterNanos)
        val lastTick = maxOf(0, index.tickCount - 1)
        val actor = filters.subject ?: filters.either
        for (i in start until blocks.size) {
            val nanos = blocks.nanos[i]
            if (nanos > filters.beforeNanos) break
            val tick = blocks.tick[i].coerceAtMost(lastTick)
            if (actor != null && !actor.matches(blocks.by[i], tick)) continue
            val from = blocks.from[i] shr 4
            val to = blocks.to[i] shr 4
            if (filters.texts.isNotEmpty() && !filters.texts.all {
                    index.names.itemMatches(to, it) || index.names.itemMatches(
                        from,
                        it
                    )
                }) continue
            val position = doubleArrayOf(blocks.x[i] + 0.5, blocks.y[i] + 0.5, blocks.z[i] + 0.5)
            if (filters.nearEntity != null || filters.nearPoint != null) {
                val point = filters.nearPoint ?: filters.nearEntity!!.at(tick)?.let { track ->
                    val j = track.index(tick)
                    doubleArrayOf(track.x[j], track.y[j], track.z[j])
                } ?: continue
                if (distance(position, point) > filters.radius) continue
            }
            if (condition != null && !condition.eval(tick).truthy) continue
            hits += SearchHit(
                nanos, nanos, labels.blockLabel(i), "${blocks.x[i]}, ${blocks.y[i]}, ${blocks.z[i]}", null,
                blocks.by[i], position[0], position[1], position[2]
            )
            if (hits.size >= limit) break
        }
        return hits
    }

    private fun spans(query: Query.Condition, compiler: Compiler, text: String, limit: Int): List<SearchHit> {
        val node = compiler.compile(query.condition)
        val hits = ArrayList<SearchHit>()
        var spanStart = -1
        var lastTrue = -1
        val tickNanos = index.tickNanos
        fun flush() {
            if (spanStart < 0) return
            val startNanos = index.nanosOf(spanStart)
            val endNanos = index.nanosOf(lastTrue) + tickNanos
            hits += SearchHit(
                startNanos,
                endNanos,
                text,
                duration(endNanos - startNanos),
                null,
                -1,
                Double.NaN,
                Double.NaN,
                Double.NaN
            )
            spanStart = -1
        }
        for (tick in 0 until index.tickCount) {
            if (node.eval(tick).truthy) {
                if (spanStart < 0) spanStart = tick
                else if (tick - lastTrue > MERGE_GAP_TICKS) {
                    flush()
                    spanStart = tick
                }
                lastTrue = tick
            } else if (spanStart >= 0 && tick - lastTrue > MERGE_GAP_TICKS) {
                flush()
                if (hits.size >= limit) return hits
            }
        }
        flush()
        return hits
    }

    private fun duration(nanos: Long): String {
        val seconds = nanos / 1_000_000_000.0
        return if (seconds < 60) String.format("%.1f s", seconds) else String.format(
            "%d:%04.1f",
            (seconds / 60).toInt(),
            seconds % 60
        )
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        const val DEFAULT_LIMIT = 2000
        const val DEFAULT_RADIUS = 8.0
        const val MERGE_GAP_TICKS = 10

        val EXAMPLES = listOf(
            "kill by:me" to "Every kill you got",
            "kill on:me" to "Every time you died to a player",
            "hurt on:me min:4" to "Hits on you for 4 or more",
            "explosion near:me within:12" to "Explosions within 12 blocks of you",
            "near(me, Steve) < 5" to "Whenever you were within 5 blocks of Steve",
            "health(me) < 8 and players() > 1" to "Below 4 hearts with others around",
            "holding(me, bow) and using(me)" to "Drawing a bow",
            "looking_at(me) == Steve and sprinting(me)" to "Sprinting toward Steve",
            "arrow by:me" to "Every arrow you shot",
            "block by:me text:tnt" to "TNT you placed",
            "chat \"gg\"" to "Chat messages containing gg",
            "title after:2:00" to "Titles shown after two minutes",
        )
    }
}
