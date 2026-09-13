package gg.sona.recast.editor.commands

import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.Moment

class AddMoment(private val moment: Moment) : EditorCommand {
    override val label: String get() = "Add moment"

    override fun apply(project: EditorProject) {
        if (project.moment(moment.id) == null) project.moments += moment
    }

    override fun revert(project: EditorProject) {
        project.moments.removeAll { it.id == moment.id }
    }
}
