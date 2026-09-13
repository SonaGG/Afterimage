package gg.sona.recast.camera.track.interpolator

import gg.sona.recast.camera.Rotation

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

    override fun translate(value: Rotation, from: Rotation, to: Rotation): Rotation = Rotation(
        AngleInterpolator.translate(value.yaw, from.yaw, to.yaw),
        value.pitch + (to.pitch - from.pitch),
        AngleInterpolator.translate(value.roll, from.roll, to.roll),
    )
}
