package gg.sona.afterimage.editor


data class TimelineEvent(val nanos: Long, val kind: EventKind, val label: String)
