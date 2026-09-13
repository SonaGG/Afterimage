package gg.sona.recast.editor.commands

import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import org.joml.Vector3d

class SetPositionHandles(private val nanos: Long, private val handleIn: Vector3d?, private val handleOut: Vector3d?) :
    EditorCommand {

    private var previousIn: Vector3d? = null
    private var previousOut: Vector3d? = null

    override val label: String get() = "Adjust bezier handles"

    override fun apply(project: EditorProject) {
        val existing = project.camera.position.at(nanos) ?: return
        previousIn = existing.handleIn
        previousOut = existing.handleOut
        project.camera.position.update(nanos) {
            it.copy(
                handleIn = handleIn?.let { v -> Vector3d(v) },
                handleOut = handleOut?.let { v -> Vector3d(v) })
        }
    }

    override fun revert(project: EditorProject) {
        project.camera.position.update(nanos) { it.copy(handleIn = previousIn, handleOut = previousOut) }
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is SetPositionHandles && next.nanos == nanos) SetPositionHandles(
            nanos,
            next.handleIn,
            next.handleOut
        ).also { it.previousIn = previousIn; it.previousOut = previousOut } else null
}
