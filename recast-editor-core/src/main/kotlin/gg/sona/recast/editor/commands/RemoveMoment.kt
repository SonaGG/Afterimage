package gg.sona.recast.editor.commands

import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.Moment
import java.util.*

class RemoveMoment(private val id: UUID) : EditorCommand {
    override val label: String get() = "Remove moment"

    private var removed: Moment? = null
    private var index = -1

    override fun apply(project: EditorProject) {
        index = project.moments.indexOfFirst { it.id == id }
        if (index >= 0) removed = project.moments.removeAt(index)
    }

    override fun revert(project: EditorProject) {
        val moment = removed ?: return
        project.moments.add(index.coerceIn(0, project.moments.size), moment)
    }
}
