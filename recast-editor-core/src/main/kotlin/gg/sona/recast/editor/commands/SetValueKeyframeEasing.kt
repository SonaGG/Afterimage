package gg.sona.recast.editor.commands

import gg.sona.recast.camera.Easing
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.ValueKey

class SetValueKeyframeEasing(private val keys: Set<ValueKey>, private val easing: Easing) : EditorCommand {

    private val previous = HashMap<ValueKey, Easing>()

    override val label: String get() = "Set easing"

    override fun apply(project: EditorProject) {
        previous.clear()
        for (key in keys) {
            val track = project.valueTrack(key.lane)
            val existing = track.at(key.nanos) ?: continue
            previous[key] = existing.easing
            track.update(key.nanos) { it.copy(easing = easing) }
        }
    }

    override fun revert(project: EditorProject) {
        for ((key, easing) in previous) project.valueTrack(key.lane).update(key.nanos) { it.copy(easing = easing) }
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is SetValueKeyframeEasing && next.keys == keys) SetValueKeyframeEasing(keys, next.easing).also {
            it.previous.putAll(previous)
        } else null
}
