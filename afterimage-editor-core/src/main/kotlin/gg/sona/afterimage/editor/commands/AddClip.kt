package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject

class AddClip(private val clip: Clip) : EditorCommand {
    override val label: String get() = "Add clip"

    override fun apply(project: EditorProject) {
        if (project.clip(clip.id) == null) project.clips += clip
    }

    override fun revert(project: EditorProject) {
        project.clips.removeAll { it.id == clip.id }
    }
}
