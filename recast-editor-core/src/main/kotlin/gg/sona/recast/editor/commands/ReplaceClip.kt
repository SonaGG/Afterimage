package gg.sona.recast.editor.commands

import gg.sona.recast.clip.Clip
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
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
