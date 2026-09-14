package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import gg.sona.afterimage.editor.LaneKind
import gg.sona.afterimage.editor.LaneState

class SetLaneState(private val kind: LaneKind, private val state: LaneState) : EditorCommand {

    private var previous: LaneState? = null

    override val label: String get() = "Change lane"

    override fun apply(project: EditorProject) {
        previous = project.lane(kind)
        project.replaceLane(kind, state)
    }

    override fun revert(project: EditorProject) {
        previous?.let { project.replaceLane(kind, it) }
    }
}
