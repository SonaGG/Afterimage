package gg.sona.recast.editor.commands

import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.TimelapseMark
import java.util.*

class ReplaceTimelapse(private val markId: UUID, private val replacement: TimelapseMark) : EditorCommand {

    private var previous: TimelapseMark? = null

    override val label: String get() = "Edit timelapse"

    override fun apply(project: EditorProject) {
        val index = project.timelapses.indexOfFirst { it.id == markId }
        if (index < 0) return
        previous = project.timelapses[index]
        project.timelapses[index] = replacement
    }

    override fun revert(project: EditorProject) {
        val original = previous ?: return
        val index = project.timelapses.indexOfFirst { it.id == markId }
        if (index >= 0) project.timelapses[index] = original
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is ReplaceTimelapse && next.markId == markId) ReplaceTimelapse(
            markId,
            next.replacement
        ).also { it.previous = previous } else null
}
