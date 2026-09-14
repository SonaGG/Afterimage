package gg.sona.recast.editor.commands

import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.look.LookSettings

class SetLook(private val field: String, private val next: LookSettings) : EditorCommand {
    private var previous: LookSettings? = null

    override val label: String get() = "Change look"

    override fun apply(project: EditorProject) {
        if (previous == null) previous = project.look.copy()
        project.look.assign(next)
    }

    override fun revert(project: EditorProject) {
        previous?.let { project.look.assign(it) }
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is SetLook && next.field == field) SetLook(field, next.next).also { it.previous = previous } else null
}
