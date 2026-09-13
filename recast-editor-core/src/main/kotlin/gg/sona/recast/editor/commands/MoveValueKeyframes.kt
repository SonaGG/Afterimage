package gg.sona.recast.editor.commands

import gg.sona.recast.camera.track.Keyframe
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.ValueKey
import gg.sona.recast.editor.ValueLane

class MoveValueKeyframes(private val keys: Set<ValueKey>, private val deltaNanos: Long) : EditorCommand {
    private var moved: List<Pair<ValueKey, ValueKey>> = emptyList()
    private var overwritten: List<Pair<ValueLane, Keyframe<Double>>> = emptyList()

    override val label: String get() = "Move keyframes"

    val resultKeys: Set<ValueKey> get() = moved.map { it.second }.toSet()

    override fun apply(project: EditorProject) {
        val ordered = if (deltaNanos > 0) keys.sortedByDescending { it.nanos } else keys.sortedBy { it.nanos }
        val replaced = ArrayList<Pair<ValueLane, Keyframe<Double>>>()
        val pairs = ArrayList<Pair<ValueKey, ValueKey>>()
        for (key in ordered) {
            val track = project.valueTrack(key.lane)
            val target = maxOf(0L, key.nanos + deltaNanos)
            val targetKey = ValueKey(key.lane, target)
            if (target != key.nanos && targetKey !in keys) track.at(target)?.let { replaced += key.lane to it }
            if (track.move(key.nanos, target)) pairs += key to targetKey
        }
        moved = pairs
        overwritten = replaced
    }

    override fun revert(project: EditorProject) {
        for ((from, to) in moved.asReversed()) project.valueTrack(from.lane).move(to.nanos, from.nanos)
        for ((lane, frame) in overwritten) project.valueTrack(lane).set(frame)
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? {
        if (next !is MoveValueKeyframes || next.keys != resultKeys) return null
        return MoveValueKeyframes(keys, deltaNanos + next.deltaNanos).also {
            it.moved = moved.map { (from, to) -> from to ValueKey(to.lane, to.nanos + next.deltaNanos) }
            it.overwritten = overwritten + next.overwritten
        }
    }
}
