package gg.sona.recast.editor.commands

import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.TimelineMarker
import java.util.*

class RemoveMarker(private val markerId: UUID) : EditorCommand {

    private var removed: TimelineMarker? = null

    override val label: String get() = "Remove marker"

    override fun apply(project: EditorProject) {
        removed = project.markers.firstOrNull { it.id == markerId }
        project.markers.removeAll { it.id == markerId }
    }

    override fun revert(project: EditorProject) {
        removed?.let { project.markers += it }
    }
}
