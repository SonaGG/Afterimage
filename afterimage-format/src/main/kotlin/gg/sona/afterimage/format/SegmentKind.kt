package gg.sona.afterimage.format


enum class SegmentKind(val id: Int) {
    DELTA(0),
    SNAPSHOT(1),
    BLOB(2);

    companion object {
        fun ofId(id: Int): SegmentKind = when (id) {
            1 -> SNAPSHOT
            2 -> BLOB
            else -> DELTA
        }
    }
}
