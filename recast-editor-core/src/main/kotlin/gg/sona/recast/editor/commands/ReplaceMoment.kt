package gg.sona.recast.editor.commands

import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.Moment
import java.util.*

class ReplaceMoment(private val id: UUID, private val replacement: Moment) : EditorCommand {
    override val label: String get() = "Edit moment"

    private var previous: Moment? = null

    override fun apply(project: EditorProject) {
        val index = project.moments.indexOfFirst { it.id == id }
        if (index < 0) return
        previous = project.moments[index]
        project.moments[index] = replacement
    }

    override fun revert(project: EditorProject) {
        val before = previous ?: return
        val index = project.moments.indexOfFirst { it.id == id }
        if (index >= 0) project.moments[index] = before
    }
}
