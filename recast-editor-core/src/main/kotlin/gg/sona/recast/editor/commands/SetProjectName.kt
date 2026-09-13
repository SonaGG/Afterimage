package gg.sona.recast.editor.commands

import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject

class SetProjectName(private val name: String) : EditorCommand {

    private var previous = ""

    override val label: String get() = "Rename project"

    override fun apply(project: EditorProject) {
        previous = project.name
        project.name = name
    }

    override fun revert(project: EditorProject) {
        project.name = previous
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is SetProjectName) SetProjectName(next.name).also { it.previous = previous } else null
}
