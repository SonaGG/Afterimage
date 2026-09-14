package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import gg.sona.afterimage.editor.TimelineMarker
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
