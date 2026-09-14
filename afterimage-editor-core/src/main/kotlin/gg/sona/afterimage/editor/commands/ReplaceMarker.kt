package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import gg.sona.afterimage.editor.TimelineMarker
import java.util.*

class ReplaceMarker(private val markerId: UUID, private val replacement: TimelineMarker) : EditorCommand {

    private var previous: TimelineMarker? = null

    override val label: String get() = "Edit marker"

    override fun apply(project: EditorProject) {
        val index = project.markers.indexOfFirst { it.id == markerId }
        if (index < 0) return
        previous = project.markers[index]
        project.markers[index] = replacement
    }

    override fun revert(project: EditorProject) {
        val original = previous ?: return
        val index = project.markers.indexOfFirst { it.id == markerId }
        if (index >= 0) project.markers[index] = original
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is ReplaceMarker && next.markerId == markerId) ReplaceMarker(
            markerId,
            next.replacement
        ).also { it.previous = previous } else null
}
