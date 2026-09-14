package gg.sona.afterimage.editor

import java.util.*

data class TimelineMarker(
    val id: UUID,
    val nanos: Long,
    val label: String,
    val color: Int,
    val kind: MarkerKind = MarkerKind.NOTE,
)
