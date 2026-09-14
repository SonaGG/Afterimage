package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import java.util.*

class ReplaceClip(private val clipId: UUID, private val replacement: Clip) : EditorCommand {

    private var previous: Clip? = null

    override val label: String get() = "Edit clip"

    override fun apply(project: EditorProject) {
        val index = project.clips.indexOfFirst { it.id == clipId }
        if (index < 0) return
        previous = project.clips[index]
        project.clips[index] = replacement
    }

    override fun revert(project: EditorProject) {
        val original = previous ?: return
        val index = project.clips.indexOfFirst { it.id == clipId }
        if (index >= 0) project.clips[index] = original
    }
}
