package gg.sona.afterimage.camera

import gg.sona.afterimage.camera.track.Extrapolation
import gg.sona.afterimage.camera.track.Keyframe
import gg.sona.afterimage.camera.track.SegmentMode
import gg.sona.afterimage.camera.track.Track
import gg.sona.afterimage.camera.track.interpolator.DoubleInterpolator
import gg.sona.afterimage.camera.track.interpolator.RotationInterpolator
import gg.sona.afterimage.camera.track.interpolator.Vector3dInterpolator
import org.joml.Vector3d

class CameraPath(val name: String = "camera") {
    val position = Track(Vector3dInterpolator, "position")
    val rotation = Track(RotationInterpolator, "rotation")
    val fov = Track(DoubleInterpolator, "fov")

    val tracks: List<Track<*>> get() = listOf(position, rotation, fov)

    val isEmpty: Boolean get() = position.isEmpty && rotation.isEmpty && fov.isEmpty

    val startNanos: Long
        get() = tracks.filter { !it.isEmpty }.minOfOrNull { it.firstNanos } ?: 0L

    val endNanos: Long get() = tracks.filter { !it.isEmpty }.maxOfOrNull { it.lastNanos } ?: 0L

    val durationNanos: Long get() = if (isEmpty) 0L else endNanos - startNanos

    val version: Int get() = position.version * 31 * 31 + rotation.version * 31 + fov.version

    var preExtrapolation: Extrapolation
        get() = position.preExtrapolation
        set(value) {
            position.preExtrapolation = value
            rotation.preExtrapolation = value
            fov.preExtrapolation = value
        }

    var postExtrapolation: Extrapolation
        get() = position.postExtrapolation
        set(value) {
            position.postExtrapolation = value
            rotation.postExtrapolation = value
            fov.postExtrapolation = value
        }

    fun keyframe(
        timeNanos: Long,
        pose: CameraPose,
        easing: Easing = Easing.LINEAR,
        mode: SegmentMode = SegmentMode.CATMULL_ROM
    ) {
        setKeeping(position, Keyframe(timeNanos, Vector3d(pose.position), easing, mode))
        setKeeping(rotation, Keyframe(timeNanos, pose.rotation, easing, mode))
        setKeeping(fov, Keyframe(timeNanos, pose.fov, easing, mode))
    }

    private fun <T> setKeeping(track: Track<T>, keyframe: Keyframe<T>) {
        val existing = track.at(keyframe.timeNanos)
        if (existing == null) {
            track.set(keyframe)
            return
        }
        val interpolator = track.interpolator
        track.set(
            keyframe.copy(
                handleIn = existing.handleIn?.let { interpolator.translate(it, existing.value, keyframe.value) },
                handleOut = existing.handleOut?.let { interpolator.translate(it, existing.value, keyframe.value) },
            )
        )
    }

    fun removeKeyframe(timeNanos: Long) {
        position.remove(timeNanos)
        rotation.remove(timeNanos)
        fov.remove(timeNanos)
    }

    fun moveKeyframe(fromNanos: Long, toNanos: Long): Boolean {
        if (fromNanos == toNanos) return true
        if (keyframeAt(fromNanos) == null) return false
        position.move(fromNanos, toNanos)
        rotation.move(fromNanos, toNanos)
        fov.move(fromNanos, toNanos)
        return true
    }

    fun setMode(timeNanos: Long, mode: SegmentMode) {
        position.update(timeNanos) { it.copy(mode = mode) }
        rotation.update(timeNanos) { it.copy(mode = mode) }
        fov.update(timeNanos) { it.copy(mode = mode) }
    }

    fun setEasing(timeNanos: Long, easing: Easing) {
        position.update(timeNanos) { it.copy(easing = easing) }
        rotation.update(timeNanos) { it.copy(easing = easing) }
        fov.update(timeNanos) { it.copy(easing = easing) }
    }

    fun snapshot(timeNanos: Long): CameraPathKey? {
        val p = position.at(timeNanos)
        val r = rotation.at(timeNanos)
        val f = fov.at(timeNanos)
        if (p == null && r == null && f == null) return null
        return CameraPathKey(timeNanos, p, r, f).deepCopy()
    }

    fun snapshots(): List<CameraPathKey> = keyframeTimes().mapNotNull { snapshot(it) }

    fun restore(key: CameraPathKey) {
        removeKeyframe(key.timeNanos)
        key.position?.let { position.set(it) }
        key.rotation?.let { rotation.set(it) }
        key.fov?.let { fov.set(it) }
    }

    fun keyframeTimes(): List<Long> =
        (position.keyframes.map { it.timeNanos } + rotation.keyframes.map { it.timeNanos } + fov.keyframes.map { it.timeNanos }).distinct()
            .sorted()

    fun keyframeAt(timeNanos: Long): CameraKeyframe? {
        val p = position.at(timeNanos)
        val r = rotation.at(timeNanos)
        val f = fov.at(timeNanos)
        if (p == null && r == null && f == null) return null
        val reference = p ?: r ?: f
        return CameraKeyframe(
            timeNanos,
            poseAt(timeNanos),
            reference?.mode ?: SegmentMode.CATMULL_ROM,
            reference?.easing ?: Easing.LINEAR
        )
    }

    fun keyframes(): List<CameraKeyframe> = keyframeTimes().mapNotNull { keyframeAt(it) }

    fun previousKeyframeTime(timeNanos: Long): Long? = keyframeTimes().lastOrNull { it < timeNanos }

    fun nextKeyframeTime(timeNanos: Long): Long? = keyframeTimes().firstOrNull { it > timeNanos }

    fun contains(timeNanos: Long): Boolean {
        if (isEmpty) return false
        if (timeNanos in startNanos..endNanos) return true
        return position.covers(timeNanos) || rotation.covers(timeNanos) || fov.covers(timeNanos)
    }

    fun poseAt(timeNanos: Long, fallback: CameraPose = CameraPose.ORIGIN): CameraPose = CameraPose(
        position.valueAt(timeNanos) ?: fallback.position,
        rotation.valueAt(timeNanos) ?: fallback.rotation,
        fov.valueAt(timeNanos) ?: fallback.fov,
    )

    fun sample(stepNanos: Long): List<Vector3d> {
        if (position.isEmpty || stepNanos <= 0L) return emptyList()
        val samples = ArrayList<Vector3d>()
        var time = position.firstNanos
        val end = position.lastNanos
        while (time <= end) {
            position.valueAt(time)?.let { samples += it }
            time += stepNanos
        }
        if (samples.isEmpty() || (end - position.firstNanos) % stepNanos != 0L) position.valueAt(end)
            ?.let { samples += it }
        return samples
    }

    private var tessellationVersion = -1
    private var tessellationTolerance = -1.0
    private var tessellationCache: List<PathSegment> = emptyList()

    fun tessellate(toleranceBlocks: Double): List<PathSegment> {
        if (tessellationVersion == position.version && tessellationTolerance == toleranceBlocks) return tessellationCache
        val frames = position.keyframes
        val result = if (frames.size < 2) emptyList() else buildTessellation(frames, toleranceBlocks)
        tessellationCache = result
        tessellationVersion = position.version
        tessellationTolerance = toleranceBlocks
        return result
    }

    private fun buildTessellation(frames: List<Keyframe<Vector3d>>, toleranceBlocks: Double): List<PathSegment> {
        val result = ArrayList<PathSegment>(frames.size - 1)
        for (index in 0 until frames.size - 1) {
            val from = frames[index]
            val to = frames[index + 1]
            val points = ArrayList<PathPoint>()
            if (from.mode == SegmentMode.HOLD) {
                points += PathPoint(Vector3d(from.value), from.timeNanos)
                points += PathPoint(Vector3d(to.value), to.timeNanos)
            } else {
                points += PathPoint(Vector3d(from.value), from.timeNanos)
                subdivide(
                    from.timeNanos,
                    to.timeNanos,
                    0.0,
                    1.0,
                    toleranceBlocks.coerceAtLeast(MIN_TOLERANCE),
                    points,
                    0
                )
            }
            result += PathSegment(from.timeNanos, to.timeNanos, from.mode, points)
        }
        return result
    }

    private fun subdivide(
        fromNanos: Long,
        toNanos: Long,
        t0: Double,
        t1: Double,
        tolerance: Double,
        out: MutableList<PathPoint>,
        depth: Int,
    ) {
        val nanosAt = fromNanos + ((toNanos - fromNanos) * t1).toLong()
        val p1 = position.valueAt(nanosAt) ?: return
        if (depth >= MAX_SUBDIVIDE_DEPTH) {
            out += PathPoint(Vector3d(p1), nanosAt)
            return
        }
        val p0 = out.last().position
        val tMid = (t0 + t1) * 0.5
        val midNanos = fromNanos + ((toNanos - fromNanos) * tMid).toLong()
        val mid = position.valueAt(midNanos)
        val flat = mid == null || mid.distance(Vector3d(p0).lerp(p1, 0.5)) <= tolerance
        if (flat) {
            out += PathPoint(Vector3d(p1), nanosAt)
        } else {
            subdivide(fromNanos, toNanos, t0, tMid, tolerance, out, depth + 1)
            subdivide(fromNanos, toNanos, tMid, t1, tolerance, out, depth + 1)
        }
    }

    fun copy(): CameraPath {
        val result = CameraPath(name)
        copyInto(result, this)
        return result
    }

    companion object {
        private const val MIN_TOLERANCE = 0.005
        private const val MAX_SUBDIVIDE_DEPTH = 10

        fun copyInto(target: CameraPath, source: CameraPath) {
            target.position.clear()
            target.rotation.clear()
            target.fov.clear()
            source.position.keyframes.forEach {
                target.position.set(
                    it.copy(
                        value = Vector3d(it.value),
                        handleIn = it.handleIn?.let { v -> Vector3d(v) },
                        handleOut = it.handleOut?.let { v -> Vector3d(v) })
                )
            }
            source.rotation.keyframes.forEach { target.rotation.set(it) }
            source.fov.keyframes.forEach { target.fov.set(it) }
            target.preExtrapolation = source.preExtrapolation
            target.postExtrapolation = source.postExtrapolation
        }
    }
}
