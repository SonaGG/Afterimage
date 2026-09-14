package gg.sona.afterimage.editor

import gg.sona.afterimage.camera.*
import gg.sona.afterimage.camera.track.SegmentMode
import org.joml.Vector3d

object PathTools {
    fun fromPoses(
        poses: List<CameraPose>,
        startNanos: Long,
        stepNanos: Long,
        easing: Easing = Easing.LINEAR
    ): CameraPath {
        val result = CameraPath("baked")
        for ((index, pose) in poses.withIndex()) result.keyframe(
            startNanos + stepNanos * index,
            pose,
            easing,
            SegmentMode.CATMULL_ROM
        )
        return result
    }

    fun reversed(path: CameraPath): CameraPath {
        val frames = path.keyframes()
        if (frames.size < 2) return path.copy()
        val start = frames.first().timeNanos
        val end = frames.last().timeNanos
        val result = CameraPath(path.name)
        for ((index, frame) in frames.withIndex()) {
            val mode = if (index > 0) frames[index - 1].mode else frame.mode
            val easing = if (index > 0) frames[index - 1].easing else frame.easing
            result.keyframe(start + end - frame.timeNanos, frame.pose, easing, mode)
        }
        return result
    }

    fun retimed(path: CameraPath, startNanos: Long, endNanos: Long): CameraPath {
        val frames = path.keyframes()
        if (frames.isEmpty()) return path.copy()
        val first = frames.first().timeNanos
        val last = frames.last().timeNanos
        val result = CameraPath(path.name)
        val sourceSpan = (last - first).toDouble()
        val targetSpan = (endNanos - startNanos).toDouble()
        for ((timeNanos, pose, mode, easing) in frames) {
            val time =
                if (sourceSpan <= 0.0) startNanos else startNanos + ((timeNanos - first) / sourceSpan * targetSpan).toLong()
            result.keyframe(time, pose, easing, mode)
        }
        return result
    }

    fun shifted(path: CameraPath, deltaNanos: Long): CameraPath {
        val result = CameraPath(path.name)
        for ((timeNanos, pose, mode, easing) in path.keyframes()) result.keyframe(
            timeNanos + deltaNanos,
            pose,
            easing,
            mode
        )
        return result
    }

    fun withUniformMode(path: CameraPath, mode: SegmentMode): CameraPath {
        val result = CameraPath(path.name)
        for ((timeNanos, pose, _, easing) in path.keyframes()) result.keyframe(timeNanos, pose, easing, mode)
        return result
    }

    fun evenlySpaced(path: CameraPath): CameraPath {
        val frames = path.keyframes()
        if (frames.size < 3) return path.copy()
        val first = frames.first().timeNanos
        val last = frames.last().timeNanos
        val step = (last - first) / (frames.size - 1)
        val result = CameraPath(path.name)
        for ((index, frame) in frames.withIndex()) result.keyframe(
            first + step * index,
            frame.pose,
            frame.easing,
            frame.mode
        )
        return result
    }

    fun byDistance(path: CameraPath): CameraPath {
        val frames = path.keyframes()
        if (frames.size < 3) return path.copy()
        val distances = DoubleArray(frames.size)
        for (index in 1 until frames.size) distances[index] =
            distances[index - 1] + frames[index].pose.position.distance(frames[index - 1].pose.position)
        val total = distances.last()
        if (total <= 0.0) return evenlySpaced(path)
        val first = frames.first().timeNanos
        val span = frames.last().timeNanos - first
        val result = CameraPath(path.name)
        for ((index, frame) in frames.withIndex()) result.keyframe(
            first + (span * (distances[index] / total)).toLong(),
            frame.pose,
            frame.easing,
            frame.mode
        )
        return result
    }

    fun simplified(path: CameraPath, toleranceBlocks: Double): CameraPath {
        val frames = path.keyframes()
        if (frames.size < 3) return path.copy()
        val keep = BooleanArray(frames.size)
        keep[0] = true
        keep[frames.size - 1] = true
        douglasPeucker(frames, 0, frames.size - 1, toleranceBlocks, keep)
        val result = CameraPath(path.name)
        for ((index, frame) in frames.withIndex()) if (keep[index]) result.keyframe(
            frame.timeNanos,
            frame.pose,
            frame.easing,
            frame.mode
        )
        return result
    }

    private fun douglasPeucker(
        frames: List<CameraKeyframe>,
        first: Int,
        last: Int,
        tolerance: Double,
        keep: BooleanArray
    ) {
        if (last - first < 2) return
        val a = frames[first].pose.position
        val b = frames[last].pose.position
        var worst = -1
        var worstDistance = 0.0
        for (index in first + 1 until last) {
            val distance = pointToSegment(frames[index].pose.position, a, b)
            if (distance > worstDistance) {
                worstDistance = distance
                worst = index
            }
        }
        if (worst < 0 || worstDistance <= tolerance) return
        keep[worst] = true
        douglasPeucker(frames, first, worst, tolerance, keep)
        douglasPeucker(frames, worst, last, tolerance, keep)
    }

    private fun pointToSegment(p: Vector3d, a: Vector3d, b: Vector3d): Double {
        val ab = Vector3d(b).sub(a)
        val length = ab.lengthSquared()
        if (length < 1e-12) return p.distance(a)
        val t = (Vector3d(p).sub(a).dot(ab) / length).coerceIn(0.0, 1.0)
        return p.distance(Vector3d(a).add(ab.mul(t)))
    }

    fun mirrored(path: CameraPath, axis: Int): CameraPath {
        val frames = path.keyframes()
        if (frames.isEmpty()) return path.copy()
        val center = Vector3d()
        for ((_, pose) in frames) center.add(pose.position)
        center.div(frames.size.toDouble())
        val result = CameraPath(path.name)
        for ((timeNanos, pose, mode, easing) in frames) {
            val position = Vector3d(pose.position)
            val forward = pose.rotation.forward()
            if (axis == 0) {
                position.x = 2 * center.x - position.x
                forward.x = -forward.x
            } else {
                position.z = 2 * center.z - position.z
                forward.z = -forward.z
            }
            val rotation = Rotation.lookingAt(Vector3d(), forward, -pose.rotation.roll)
            result.keyframe(timeNanos, CameraPose(position, rotation, pose.fov), easing, mode)
        }
        return result
    }

    fun facingTravel(path: CameraPath): CameraPath {
        val frames = path.keyframes()
        if (frames.size < 2) return path.copy()
        val result = CameraPath(path.name)
        for ((index, frame) in frames.withIndex()) {
            val from = if (index < frames.size - 1) frame.pose.position else frames[index - 1].pose.position
            val to = if (index < frames.size - 1) frames[index + 1].pose.position else frame.pose.position
            val rotation = if (from.distanceSquared(to) < 1e-8) frame.pose.rotation else Rotation.lookingAt(
                from,
                to,
                frame.pose.rotation.roll
            )
            result.keyframe(
                frame.timeNanos,
                CameraPose(Vector3d(frame.pose.position), rotation, frame.pose.fov),
                frame.easing,
                frame.mode
            )
        }
        return result
    }

    fun levelled(path: CameraPath): CameraPath {
        val result = CameraPath(path.name)
        for ((timeNanos, pose, mode, easing) in path.keyframes()) result.keyframe(
            timeNanos,
            CameraPose(
                Vector3d(pose.position),
                Rotation(pose.rotation.yaw, pose.rotation.pitch, 0.0),
                pose.fov
            ),
            easing,
            mode
        )
        return result
    }

    fun withUniformFov(path: CameraPath, fov: Double): CameraPath {
        val result = CameraPath(path.name)
        for ((timeNanos, pose, mode, easing) in path.keyframes()) result.keyframe(
            timeNanos,
            CameraPose(Vector3d(pose.position), pose.rotation, fov),
            easing,
            mode
        )
        return result
    }
}
