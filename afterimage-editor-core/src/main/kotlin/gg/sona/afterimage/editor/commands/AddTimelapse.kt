package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import gg.sona.afterimage.editor.TimelapseMark

class AddTimelapse(private val mark: TimelapseMark) : EditorCommand {
    override val label: String get() = "Add timelapse"

    override fun apply(project: EditorProject) {
        if (project.timelapse(mark.id) == null) project.timelapses += mark
    }

    override fun revert(project: EditorProject) {
        project.timelapses.removeAll { it.id == mark.id }
    }
}
