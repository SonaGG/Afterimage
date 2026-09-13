package gg.sona.recast.editor.commands

import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.TimelineMarker

class AddMarker(private val marker: TimelineMarker) : EditorCommand {
    override val label: String get() = "Add marker"

    override fun apply(project: EditorProject) {
        if (project.marker(marker.id) == null) project.markers += marker
    }

    override fun revert(project: EditorProject) {
        project.markers.removeAll { it.id == marker.id }
    }
}
