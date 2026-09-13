package gg.sona.recast.editor

data class LaneState(val kind: LaneKind, val name: String, val muted: Boolean = false, val locked: Boolean = false)
