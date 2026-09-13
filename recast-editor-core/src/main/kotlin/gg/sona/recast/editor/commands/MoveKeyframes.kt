package gg.sona.recast.editor.commands

import gg.sona.recast.camera.CameraPathKey
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject

class MoveKeyframes(private val times: Set<Long>, private val deltaNanos: Long) : EditorCommand {

    private var moved: List<Pair<Long, Long>> = emptyList()
    private var overwritten: List<CameraPathKey> = emptyList()

    override val label: String get() = "Move keyframes"

    override fun apply(project: EditorProject) {
        val ordered = if (deltaNanos > 0) times.sortedDescending() else times.sorted()
        val replaced = ArrayList<CameraPathKey>()
        val pairs = ArrayList<Pair<Long, Long>>()
        for (time in ordered) {
            val target = maxOf(0L, time + deltaNanos)
            if (target != time && target !in times) project.camera.snapshot(target)?.let { replaced += it }
            if (project.camera.moveKeyframe(time, target)) pairs += time to target
        }
        moved = pairs
        overwritten = replaced
    }

    override fun revert(project: EditorProject) {
        for ((from, to) in moved.asReversed()) project.camera.moveKeyframe(to, from)
        for (key in overwritten) project.camera.restore(key)
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? {
        if (next !is MoveKeyframes) return null
        val targets = moved.map { it.second }.toSet()
        if (next.times != targets) return null
        return MoveKeyframes(times, deltaNanos + next.deltaNanos).also {
            it.moved = moved.map { (from, to) -> from to (to + next.deltaNanos) }
            it.overwritten = overwritten + next.overwritten
        }
    }
}
