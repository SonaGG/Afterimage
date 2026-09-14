package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject

class SetAimTarget(private val target: Int?) : EditorCommand {
    private var previous: Int? = null

    override val label: String get() = if (target == null) "Stop aiming camera path" else "Aim camera path at entity"

    override fun apply(project: EditorProject) {
        previous = project.aimTargetId
        project.aimTargetId = target
    }

    override fun revert(project: EditorProject) {
        project.aimTargetId = previous
    }
}
