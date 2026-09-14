package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject

class SetInOutPoints(private val inNanos: Long, private val outNanos: Long) : EditorCommand {

    private var previousIn = 0L
    private var previousOut = 0L

    override val label: String get() = "Set in/out"

    override fun apply(project: EditorProject) {
        previousIn = project.inPointNanos
        previousOut = project.outPointNanos
        project.inPointNanos = inNanos
        project.outPointNanos = outNanos
    }

    override fun revert(project: EditorProject) {
        project.inPointNanos = previousIn
        project.outPointNanos = previousOut
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is SetInOutPoints) SetInOutPoints(next.inNanos, next.outNanos).also {
            it.previousIn = previousIn
            it.previousOut = previousOut
        } else null
}
