package gg.sona.recast.editor.commands

import gg.sona.recast.camera.Easing
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject

class SetKeyframeEasing(private val times: Set<Long>, private val easing: Easing) : EditorCommand {

    private val previous = HashMap<Long, Easing>()

    override val label: String get() = "Set easing"

    override fun apply(project: EditorProject) {
        previous.clear()
        for (time in times) {
            val existing = project.camera.keyframeAt(time) ?: continue
            previous[time] = existing.easing
            project.camera.setEasing(time, easing)
        }
    }

    override fun revert(project: EditorProject) {
        for ((time, easing) in previous) project.camera.setEasing(time, easing)
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is SetKeyframeEasing && next.times == times) SetKeyframeEasing(times, next.easing).also {
            it.previous.putAll(previous)
        } else null
}
