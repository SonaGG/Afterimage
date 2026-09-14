package gg.sona.afterimage.camera.track.interpolator

import org.joml.Vector3d

object Vector3dInterpolator : ValueInterpolator<Vector3d> {
    override fun lerp(a: Vector3d, b: Vector3d, t: Double): Vector3d = Vector3d(a).lerp(b, t)

    override fun distance(a: Vector3d, b: Vector3d): Double = a.distance(b)

    override fun bezier(p1: Vector3d, control1: Vector3d, control2: Vector3d, p2: Vector3d, t: Double): Vector3d =
        Vector3d(
            DoubleInterpolator.bezier(p1.x, control1.x, control2.x, p2.x, t),
            DoubleInterpolator.bezier(p1.y, control1.y, control2.y, p2.y, t),
            DoubleInterpolator.bezier(p1.z, control1.z, control2.z, p2.z, t),
        )

    override fun tangentControl(previous: Vector3d, current: Vector3d, next: Vector3d, scale: Double): Vector3d =
        Vector3d(next).sub(previous).mul(scale).add(current)

    override fun translate(value: Vector3d, from: Vector3d, to: Vector3d): Vector3d = Vector3d(value).add(to).sub(from)
}
