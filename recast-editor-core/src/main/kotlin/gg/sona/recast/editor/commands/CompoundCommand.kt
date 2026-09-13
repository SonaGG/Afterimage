package gg.sona.recast.editor.commands

import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject

class CompoundCommand(override val label: String, private val commands: List<EditorCommand>) : EditorCommand {
    override fun apply(project: EditorProject) {
        for (command in commands) command.apply(project)
    }

    override fun revert(project: EditorProject) {
        for (command in commands.asReversed()) command.revert(project)
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? {
        if (next !is CompoundCommand || next.commands.size != commands.size) return null
        val merged = ArrayList<EditorCommand>(commands.size)
        for (index in commands.indices) merged += commands[index].mergeWith(next.commands[index]) ?: return null
        return CompoundCommand(next.label, merged)
    }
}
