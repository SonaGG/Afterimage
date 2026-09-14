package gg.sona.recast.editor.commands

import gg.sona.recast.camera.track.Keyframe
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.PackState

class RemovePackKeyframes(private val times: Set<Long>) : EditorCommand {

    private var removed: List<Keyframe<PackState>> = emptyList()

    override val label: String get() = "Delete texture pack keyframe"

    override fun apply(project: EditorProject) {
        removed = times.mapNotNull { project.packs.at(it) }
        times.forEach { project.packs.remove(it) }
    }

    override fun revert(project: EditorProject) {
        removed.forEach { project.packs.set(it) }
    }
}
