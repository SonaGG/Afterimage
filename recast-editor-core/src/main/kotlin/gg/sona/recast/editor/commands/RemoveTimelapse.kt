package gg.sona.recast.editor.commands

import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.TimelapseMark
import java.util.*

class RemoveTimelapse(private val markId: UUID) : EditorCommand {

    private var removed: TimelapseMark? = null

    override val label: String get() = "Remove timelapse"

    override fun apply(project: EditorProject) {
        removed = project.timelapses.firstOrNull { it.id == markId }
        project.timelapses.removeAll { it.id == markId }
    }

    override fun revert(project: EditorProject) {
        removed?.let { project.timelapses += it }
    }
}
