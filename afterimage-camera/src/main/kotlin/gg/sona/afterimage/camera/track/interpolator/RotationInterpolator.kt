package gg.sona.afterimage.camera.track.interpolator

import gg.sona.afterimage.camera.Rotation

object RotationInterpolator : ValueInterpolator<Rotation> {
    override fun lerp(
        a: Rotation,
        b: Rotation,
        t: Double
    ): Rotation =
        Rotation(
            AngleInterpolator.lerp(a.yaw, b.yaw, t),
            DoubleInterpolator.lerp(a.pitch, b.pitch, t),
            AngleInterpolator.lerp(a.roll, b.roll, t)
        )

    override fun distance(a: Rotation, b: Rotation): Double {
        val dYaw = AngleInterpolator.distance(a.yaw, b.yaw)
        val dPitch = DoubleInterpolator.distance(a.pitch, b.pitch)
        val dRoll = AngleInterpolator.distance(a.roll, b.roll)
        return kotlin.math.sqrt(dYaw * dYaw + dPitch * dPitch + dRoll * dRoll)
    }

    override fun bezier(
        p1: Rotation,
        control1: Rotation,
        control2: Rotation,
        p2: Rotation,
        t: Double
    ): Rotation =
        Rotation(
            AngleInterpolator.bezier(p1.yaw, control1.yaw, control2.yaw, p2.yaw, t),
            DoubleInterpolator.bezier(p1.pitch, control1.pitch, control2.pitch, p2.pitch, t),
            AngleInterpolator.bezier(p1.roll, control1.roll, control2.roll, p2.roll, t),
        )

    override fun tangentControl(
        previous: Rotation,
        current: Rotation,
        next: Rotation,
        scale: Double
    ): Rotation =
        Rotation(
            AngleInterpolator.tangentControl(previous.yaw, current.yaw, next.yaw, scale),
            DoubleInterpolator.tangentControl(previous.pitch, current.pitch, next.pitch, scale),
            AngleInterpolator.tangentControl(previous.roll, current.roll, next.roll, scale),
        )

    override fun catmullRom(p0: Rotation, p1: Rotation, p2: Rotation, p3: Rotation, weights: DoubleArray, t: Double): Rotation = Rotation(
        AngleInterpolator.catmullRom(p0.yaw, p1.yaw, p2.yaw, p3.yaw, weights, t),
        DoubleInterpolator.catmullRom(p0.pitch, p1.pitch, p2.pitch, p3.pitch, weights, t),
        AngleInterpolator.catmullRom(p0.roll, p1.roll, p2.roll, p3.roll, weights, t),
    )

    override fun translate(value: Rotation, from: Rotation, to: Rotation): Rotation = Rotation(
        AngleInterpolator.translate(value.yaw, from.yaw, to.yaw),
        value.pitch + (to.pitch - from.pitch),
        AngleInterpolator.translate(value.roll, from.roll, to.roll),
    )
}
