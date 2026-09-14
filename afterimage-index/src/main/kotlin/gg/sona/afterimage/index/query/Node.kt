package gg.sona.afterimage.index.query

fun interface Node {
    fun eval(tick: Int): Value
}
