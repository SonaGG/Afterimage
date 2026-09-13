package gg.sona.recast.flashback


class RuleContext(val recent: List<SemanticEvent>) {

    inline fun <reified T : SemanticEvent> recentWithin(nanos: Long, windowNanos: Long): List<T> =
        recent.filterIsInstance<T>().filter { nanos - it.nanos in 0..windowNanos }
}
