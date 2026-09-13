package gg.sona.recast.editor


data class TimelineEvent(val nanos: Long, val kind: EventKind, val label: String)
