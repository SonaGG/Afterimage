package gg.sona.recast.index.query

fun interface Node {
    fun eval(tick: Int): Value
}
