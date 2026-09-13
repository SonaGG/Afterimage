package gg.sona.recast.index.query

import gg.sona.recast.index.IndexEventKind

class SearchHit(
    val nanos: Long,
    val endNanos: Long,
    val label: String,
    val detail: String,
    val kind: IndexEventKind?,
    val entityId: Int,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    val isSpan: Boolean get() = endNanos > nanos
    val hasPosition: Boolean get() = !x.isNaN()
}
