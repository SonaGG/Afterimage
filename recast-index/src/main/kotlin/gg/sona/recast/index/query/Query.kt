package gg.sona.recast.index.query

sealed class Query {
    data class Events(val kinds: Set<EventKindSpec>, val filters: List<EventFilter>, val condition: Expr?) : Query()
    data class Condition(val condition: Expr) : Query()
}
