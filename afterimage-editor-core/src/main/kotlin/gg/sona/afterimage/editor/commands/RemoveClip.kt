package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import java.util.*

class RemoveClip(private val clipId: UUID) : EditorCommand {
    private var removed: Clip? = null
    private var index = -1

    override val label: String get() = "Remove clip"

    override fun apply(project: EditorProject) {
        index = project.clips.indexOfFirst { it.id == clipId }
        if (index >= 0) removed = project.clips.removeAt(index)
    }

    override fun revert(project: EditorProject) {
        val clip = removed ?: return
        project.clips.add(index.coerceIn(0, project.clips.size), clip)
    }
}
