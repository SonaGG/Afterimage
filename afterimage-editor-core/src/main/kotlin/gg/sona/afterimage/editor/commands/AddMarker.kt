package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import gg.sona.afterimage.editor.TimelineMarker

class AddMarker(private val marker: TimelineMarker) : EditorCommand {
    override val label: String get() = "Add marker"

    override fun apply(project: EditorProject) {
        if (project.marker(marker.id) == null) project.markers += marker
    }

    override fun revert(project: EditorProject) {
        project.markers.removeAll { it.id == marker.id }
    }
}
