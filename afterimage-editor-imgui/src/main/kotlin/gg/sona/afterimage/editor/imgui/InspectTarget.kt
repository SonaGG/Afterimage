package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.editor.LaneKind

sealed class InspectTarget {
    data object Project : InspectTarget()
    data class Entity(
        val id: Int,
        val name: String,
        val isPlayer: Boolean,
        val isRecorder: Boolean,
        val uuid: String?
    ) : InspectTarget()

    data class Lane(val kind: LaneKind) : InspectTarget()
}
