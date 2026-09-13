package gg.sona.recast.editor.imgui

import gg.sona.recast.camera.CameraPose
import gg.sona.recast.camera.Easing
import gg.sona.recast.camera.Rotation
import gg.sona.recast.camera.track.SegmentMode
import gg.sona.recast.camera.track.Track
import gg.sona.recast.core.time.Nanos
import gg.sona.recast.editor.*
import gg.sona.recast.editor.commands.SetCameraKeyframe
import gg.sona.recast.editor.commands.SetValueKeyframe
import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.sqrt

sealed class GraphChannel(
    val id: String,
    val label: String,
    val group: String,
    val color: EditorTheme.Rgb,
    val unit: String,
    val speedGroup: String,
) {
    class Key(val timeNanos: Long, val value: Double, val easing: Easing, val mode: SegmentMode, val index: Int)

    abstract fun enabled(project: EditorProject): Boolean

    abstract fun keys(project: EditorProject): List<Key>

    abstract fun valueAt(project: EditorProject, nanos: Long): Double?

    abstract fun firstNanos(project: EditorProject): Long

    abstract fun lastNanos(project: EditorProject): Long

    abstract fun isSelected(selection: Selection, nanos: Long): Boolean

    abstract fun select(selection: Selection, nanos: Long, additive: Boolean): Selection

    abstract fun deselect(selection: Selection, nanos: Long): Selection

    abstract fun valueCommand(project: EditorProject, nanos: Long, value: Double): EditorCommand?

    abstract fun insertKey(session: EditorSession, nanos: Long)

    abstract fun format(value: Double): String

    abstract fun spatialSlope(project: EditorProject, segment: Int, atEnd: Boolean): Double

    abstract fun spatialSpeed(project: EditorProject, segment: Int, atEnd: Boolean): Double

    abstract fun speedAt(project: EditorProject, nanos: Long): Double?

    abstract fun segmentAt(project: EditorProject, nanos: Long): Int

    abstract fun segmentSpanNanos(project: EditorProject, segment: Int): Long

    protected fun <T> probe(track: Track<T>, segment: Int, atEnd: Boolean, measure: (T, T) -> Double): Double {
        if (segment < 0 || segment >= track.keyframes.size - 1) return 0.0
        val epsilon = 1e-3
        return if (atEnd) measure(track.evaluate(segment, 1.0 - epsilon), track.evaluate(segment, 1.0)) / epsilon
        else measure(track.evaluate(segment, 0.0), track.evaluate(segment, epsilon)) / epsilon
    }

    protected fun <T> numericSpeed(track: Track<T>, nanos: Long, distance: (T, T) -> Double): Double? {
        if (track.keyframes.size < 2) return null
        val segment = track.segmentAt(nanos)
        val span =
            if (segment >= 0) track.keyframes[segment + 1].timeNanos - track.keyframes[segment].timeNanos else Nanos.PER_SECOND
        val delta = maxOf(Nanos.PER_MILLI / 4L, minOf(Nanos.ofMillis(8), span / 64L))
        val key = track.at(nanos)
        val (from, to) = when {
            key != null && nanos >= track.lastNanos -> nanos - delta to nanos
            key != null -> nanos to nanos + delta
            else -> nanos - delta to nanos + delta
        }
        val a = track.valueAt(from) ?: return null
        val b = track.valueAt(to) ?: return null
        return distance(a, b) / ((to - from).toDouble() / Nanos.PER_SECOND)
    }

    class Camera(val part: Part, val component: Int) : GraphChannel(
        "camera.${part.name.lowercase()}.$component",
        part.labels[component],
        "Camera",
        part.colors[component],
        part.unit,
        "camera.${part.name.lowercase()}",
    ) {
        enum class Part(val labels: List<String>, val colors: List<EditorTheme.Rgb>, val unit: String) {
            POSITION(
                listOf("Position X", "Position Y", "Position Z"),
                listOf(EditorTheme.AXIS_X, EditorTheme.AXIS_Y, EditorTheme.AXIS_Z),
                "blocks"
            ),
            ROTATION(
                listOf("Yaw", "Pitch", "Roll"),
                listOf(EditorTheme.WARNING, EditorTheme.PURPLE, EditorTheme.PINK),
                "deg"
            ),
            FOV(listOf("Field of view"), listOf(EditorTheme.TEAL), "deg"),
        }

        override fun enabled(project: EditorProject): Boolean = !project.lane(LaneKind.CAMERA).muted

        private fun value(project: EditorProject, nanos: Long): Double? = when (part) {
            Part.POSITION -> project.camera.position.valueAt(nanos)?.let { component(it) }
            Part.ROTATION -> project.camera.rotation.valueAt(nanos)?.let { component(it) }
            Part.FOV -> project.camera.fov.valueAt(nanos)
        }

        private fun component(vector: Vector3d): Double = when (component) {
            0 -> vector.x
            1 -> vector.y
            else -> vector.z
        }

        private fun component(rotation: Rotation): Double = when (component) {
            0 -> rotation.yaw
            1 -> rotation.pitch
            else -> rotation.roll
        }

        override fun keys(project: EditorProject): List<Key> = when (part) {
            Part.POSITION -> project.camera.position.keyframes.mapIndexed { index, key ->
                Key(
                    key.timeNanos,
                    component(key.value),
                    key.easing,
                    key.mode,
                    index
                )
            }

            Part.ROTATION -> project.camera.rotation.keyframes.mapIndexed { index, key ->
                Key(
                    key.timeNanos,
                    component(key.value),
                    key.easing,
                    key.mode,
                    index
                )
            }

            Part.FOV -> project.camera.fov.keyframes.mapIndexed { index, key ->
                Key(
                    key.timeNanos,
                    key.value,
                    key.easing,
                    key.mode,
                    index
                )
            }
        }

        override fun valueAt(project: EditorProject, nanos: Long): Double? = value(project, nanos)

        override fun firstNanos(project: EditorProject): Long = project.camera.startNanos

        override fun lastNanos(project: EditorProject): Long = project.camera.endNanos

        override fun isSelected(selection: Selection, nanos: Long): Boolean = nanos in selection.keyframeTimes

        override fun select(selection: Selection, nanos: Long, additive: Boolean): Selection =
            selection.withKeyframe(nanos, additive)

        override fun deselect(selection: Selection, nanos: Long): Selection = selection.withoutKeyframe(nanos)

        override fun valueCommand(project: EditorProject, nanos: Long, value: Double): EditorCommand? {
            val frame = project.camera.keyframeAt(nanos) ?: return null
            val pose = frame.pose
            val next = when (part) {
                Part.POSITION -> CameraPose(
                    Vector3d(
                        if (component == 0) value else pose.position.x,
                        if (component == 1) value else pose.position.y,
                        if (component == 2) value else pose.position.z,
                    ), pose.rotation, pose.fov
                )

                Part.ROTATION -> CameraPose(
                    pose.position,
                    Rotation(
                        if (component == 0) value else pose.rotation.yaw,
                        if (component == 1) value.coerceIn(-90.0, 90.0) else pose.rotation.pitch,
                        if (component == 2) value else pose.rotation.roll,
                    ), pose.fov
                )

                Part.FOV -> CameraPose(pose.position, pose.rotation, value.coerceIn(1.0, 175.0))
            }
            return SetCameraKeyframe(nanos, next, frame.easing, frame.mode)
        }

        override fun insertKey(session: EditorSession, nanos: Long) {
            val camera = session.project.camera
            if (camera.isEmpty) return
            val previous = camera.previousKeyframeTime(nanos)?.let { camera.keyframeAt(it) }
            session.execute(
                SetCameraKeyframe(
                    nanos, camera.poseAt(nanos),
                    previous?.easing ?: session.defaultEasing,
                    previous?.mode ?: session.defaultKeyframeMode
                )
            )
            session.selection = Selection(keyframeTimes = setOf(nanos))
        }

        override fun format(value: Double): String = when (part) {
            Part.POSITION -> String.format("%.2f", value)
            else -> String.format("%.1f°", value)
        }

        override fun spatialSlope(project: EditorProject, segment: Int, atEnd: Boolean): Double = when (part) {
            Part.POSITION -> probe(project.camera.position, segment, atEnd) { a, b -> component(b) - component(a) }
            Part.ROTATION -> probe(project.camera.rotation, segment, atEnd) { a, b ->
                if (component == 1) b.pitch - a.pitch else Rotation.shortestDelta(component(a), component(b))
            }

            Part.FOV -> probe(project.camera.fov, segment, atEnd) { a, b -> b - a }
        }

        override fun spatialSpeed(project: EditorProject, segment: Int, atEnd: Boolean): Double = when (part) {
            Part.POSITION -> probe(project.camera.position, segment, atEnd) { a, b -> a.distance(b) }
            Part.ROTATION -> probe(project.camera.rotation, segment, atEnd) { a, b -> rotationDistance(a, b) }
            Part.FOV -> abs(probe(project.camera.fov, segment, atEnd) { a, b -> b - a })
        }

        override fun speedAt(project: EditorProject, nanos: Long): Double? = when (part) {
            Part.POSITION -> numericSpeed(project.camera.position, nanos) { a, b -> a.distance(b) }
            Part.ROTATION -> numericSpeed(project.camera.rotation, nanos) { a, b -> rotationDistance(a, b) }
            Part.FOV -> numericSpeed(project.camera.fov, nanos) { a, b -> abs(b - a) }
        }

        private fun rotationDistance(a: Rotation, b: Rotation): Double {
            val yaw = Rotation.shortestDelta(a.yaw, b.yaw)
            val pitch = b.pitch - a.pitch
            val roll = Rotation.shortestDelta(a.roll, b.roll)
            return sqrt(yaw * yaw + pitch * pitch + roll * roll)
        }

        override fun segmentAt(project: EditorProject, nanos: Long): Int = project.camera.position.segmentAt(nanos)

        override fun segmentSpanNanos(project: EditorProject, segment: Int): Long {
            val keys = project.camera.position.keyframes
            if (segment < 0 || segment >= keys.size - 1) return 0L
            return keys[segment + 1].timeNanos - keys[segment].timeNanos
        }
    }

    class Value(val lane: ValueLane) : GraphChannel(
        "lane.${lane.name.lowercase()}",
        lane.label,
        "Tracks",
        LANE_COLORS[lane] ?: EditorTheme.ACCENT_TEXT,
        LANE_UNITS[lane] ?: "",
        "lane.${lane.name.lowercase()}",
    ) {
        private fun track(project: EditorProject): Track<Double> = project.valueTrack(lane)

        override fun enabled(project: EditorProject): Boolean = !project.lane(lane.kind).muted

        override fun keys(project: EditorProject): List<Key> =
            track(project).keyframes.mapIndexed { index, key ->
                Key(
                    key.timeNanos,
                    key.value,
                    key.easing,
                    key.mode,
                    index
                )
            }

        override fun valueAt(project: EditorProject, nanos: Long): Double? = track(project).valueAt(nanos)

        override fun firstNanos(project: EditorProject): Long = track(project).firstNanos

        override fun lastNanos(project: EditorProject): Long = track(project).lastNanos

        override fun isSelected(selection: Selection, nanos: Long): Boolean =
            ValueKey(lane, nanos) in selection.valueKeys

        override fun select(selection: Selection, nanos: Long, additive: Boolean): Selection =
            selection.withValueKeyframe(lane, nanos, additive)

        override fun deselect(selection: Selection, nanos: Long): Selection =
            selection.copy(valueKeys = selection.valueKeys - ValueKey(lane, nanos))

        override fun valueCommand(project: EditorProject, nanos: Long, value: Double): EditorCommand? {
            val frame = track(project).at(nanos) ?: return null
            return SetValueKeyframe(lane, nanos, value.coerceIn(lane.min, lane.max), frame.mode, frame.easing)
        }

        override fun insertKey(session: EditorSession, nanos: Long) {
            val track = track(session.project)
            val value = track.valueAt(nanos) ?: lane.default
            val previous = track.previous(nanos)
            session.execute(
                SetValueKeyframe(
                    lane,
                    nanos,
                    value,
                    previous?.mode ?: SegmentMode.LINEAR,
                    previous?.easing ?: Easing.LINEAR
                )
            )
            session.selection = Selection(valueKeys = setOf(ValueKey(lane, nanos)))
        }

        override fun format(value: Double): String = lane.format(value)

        override fun spatialSlope(project: EditorProject, segment: Int, atEnd: Boolean): Double =
            probe(track(project), segment, atEnd) { a, b -> b - a }

        override fun spatialSpeed(project: EditorProject, segment: Int, atEnd: Boolean): Double =
            abs(spatialSlope(project, segment, atEnd))

        override fun speedAt(project: EditorProject, nanos: Long): Double? =
            numericSpeed(track(project), nanos) { a, b -> abs(b - a) }

        override fun segmentAt(project: EditorProject, nanos: Long): Int = track(project).segmentAt(nanos)

        override fun segmentSpanNanos(project: EditorProject, segment: Int): Long {
            val keys = track(project).keyframes
            if (segment < 0 || segment >= keys.size - 1) return 0L
            return keys[segment + 1].timeNanos - keys[segment].timeNanos
        }
    }

    companion object {
        val LANE_COLORS = mapOf(
            ValueLane.SPEED to EditorTheme.SUCCESS,
            ValueLane.FOV to EditorTheme.TEAL,
            ValueLane.TIME_OF_DAY to EditorTheme.WARNING,
            ValueLane.SHAKE to EditorTheme.PINK,
            ValueLane.SHAKE_FREQUENCY to EditorTheme.PURPLE,
            ValueLane.FREEZE to EditorTheme.TEXT_MUTED,
        )

        val LANE_UNITS = mapOf(
            ValueLane.SPEED to "x",
            ValueLane.FOV to "deg",
            ValueLane.TIME_OF_DAY to "ticks",
            ValueLane.SHAKE to "",
            ValueLane.SHAKE_FREQUENCY to "Hz",
            ValueLane.FREEZE to "s",
        )

        val ALL: List<GraphChannel> = listOf(
            Camera(Camera.Part.POSITION, 0),
            Camera(Camera.Part.POSITION, 1),
            Camera(Camera.Part.POSITION, 2),
            Camera(Camera.Part.ROTATION, 0),
            Camera(Camera.Part.ROTATION, 1),
            Camera(Camera.Part.ROTATION, 2),
            Camera(Camera.Part.FOV, 0),
        ) + ValueLane.entries.map { Value(it) }
    }
}
