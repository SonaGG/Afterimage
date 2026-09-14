package gg.sona.afterimage.editor

import gg.sona.afterimage.camera.track.interpolator.AngleInterpolator
import gg.sona.afterimage.camera.track.interpolator.DoubleInterpolator
import gg.sona.afterimage.camera.track.interpolator.ValueInterpolator
import kotlin.math.sqrt

object ViewStateInterpolator : ValueInterpolator<ViewState> {
    private fun sameIdentity(a: ViewState, b: ViewState): Boolean =
        a.mode == b.mode && a.targetEntityId == b.targetEntityId && a.bodyPart == b.bodyPart

    override fun lerp(a: ViewState, b: ViewState, t: Double): ViewState {
        if (!sameIdentity(a, b)) return a
        return a.copy(
            followLookAtTarget = b.followLookAtTarget,
            orbitDistance = DoubleInterpolator.lerp(a.orbitDistance, b.orbitDistance, t),
            orbitPitch = DoubleInterpolator.lerp(a.orbitPitch, b.orbitPitch, t),
            orbitYawOffset = AngleInterpolator.lerp(a.orbitYawOffset, b.orbitYawOffset, t),
            orbitHeight = DoubleInterpolator.lerp(a.orbitHeight, b.orbitHeight, t),
            orbitDegreesPerSecond = DoubleInterpolator.lerp(a.orbitDegreesPerSecond, b.orbitDegreesPerSecond, t),
            followOffsetX = DoubleInterpolator.lerp(a.followOffsetX, b.followOffsetX, t),
            followOffsetY = DoubleInterpolator.lerp(a.followOffsetY, b.followOffsetY, t),
            followOffsetZ = DoubleInterpolator.lerp(a.followOffsetZ, b.followOffsetZ, t),
            chaseDistance = DoubleInterpolator.lerp(a.chaseDistance, b.chaseDistance, t),
            chaseHeight = DoubleInterpolator.lerp(a.chaseHeight, b.chaseHeight, t),
            chaseStiffness = DoubleInterpolator.lerp(a.chaseStiffness, b.chaseStiffness, t),
            chaseDamping = DoubleInterpolator.lerp(a.chaseDamping, b.chaseDamping, t),
            targetOffsetX = DoubleInterpolator.lerp(a.targetOffsetX, b.targetOffsetX, t),
            targetOffsetY = DoubleInterpolator.lerp(a.targetOffsetY, b.targetOffsetY, t),
            targetOffsetZ = DoubleInterpolator.lerp(a.targetOffsetZ, b.targetOffsetZ, t),
        )
    }

    override fun distance(a: ViewState, b: ViewState): Double {
        if (!sameIdentity(a, b)) return 0.0
        val components = doubleArrayOf(
            DoubleInterpolator.distance(a.orbitDistance, b.orbitDistance),
            DoubleInterpolator.distance(a.orbitPitch, b.orbitPitch),
            AngleInterpolator.distance(a.orbitYawOffset, b.orbitYawOffset),
            DoubleInterpolator.distance(a.orbitHeight, b.orbitHeight),
            DoubleInterpolator.distance(a.followOffsetX, b.followOffsetX),
            DoubleInterpolator.distance(a.followOffsetY, b.followOffsetY),
            DoubleInterpolator.distance(a.followOffsetZ, b.followOffsetZ),
            DoubleInterpolator.distance(a.chaseDistance, b.chaseDistance),
            DoubleInterpolator.distance(a.chaseHeight, b.chaseHeight),
            DoubleInterpolator.distance(a.targetOffsetX, b.targetOffsetX),
            DoubleInterpolator.distance(a.targetOffsetY, b.targetOffsetY),
            DoubleInterpolator.distance(a.targetOffsetZ, b.targetOffsetZ),
        )
        return sqrt(components.sumOf { it * it })
    }

    override fun bezier(p1: ViewState, control1: ViewState, control2: ViewState, p2: ViewState, t: Double): ViewState {
        if (!sameIdentity(p1, p2)) return p1
        return p1.copy(
            followLookAtTarget = p2.followLookAtTarget,
            orbitDistance = DoubleInterpolator.bezier(
                p1.orbitDistance, control1.orbitDistance, control2.orbitDistance, p2.orbitDistance, t
            ),
            orbitPitch = DoubleInterpolator.bezier(
                p1.orbitPitch,
                control1.orbitPitch,
                control2.orbitPitch,
                p2.orbitPitch,
                t
            ),
            orbitYawOffset = AngleInterpolator.bezier(
                p1.orbitYawOffset, control1.orbitYawOffset, control2.orbitYawOffset, p2.orbitYawOffset, t
            ),
            orbitHeight = DoubleInterpolator.bezier(
                p1.orbitHeight,
                control1.orbitHeight,
                control2.orbitHeight,
                p2.orbitHeight,
                t
            ),
            orbitDegreesPerSecond = DoubleInterpolator.bezier(
                p1.orbitDegreesPerSecond,
                control1.orbitDegreesPerSecond,
                control2.orbitDegreesPerSecond,
                p2.orbitDegreesPerSecond,
                t
            ),
            followOffsetX = DoubleInterpolator.bezier(
                p1.followOffsetX,
                control1.followOffsetX,
                control2.followOffsetX,
                p2.followOffsetX,
                t
            ),
            followOffsetY = DoubleInterpolator.bezier(
                p1.followOffsetY,
                control1.followOffsetY,
                control2.followOffsetY,
                p2.followOffsetY,
                t
            ),
            followOffsetZ = DoubleInterpolator.bezier(
                p1.followOffsetZ,
                control1.followOffsetZ,
                control2.followOffsetZ,
                p2.followOffsetZ,
                t
            ),
            chaseDistance = DoubleInterpolator.bezier(
                p1.chaseDistance,
                control1.chaseDistance,
                control2.chaseDistance,
                p2.chaseDistance,
                t
            ),
            chaseHeight = DoubleInterpolator.bezier(
                p1.chaseHeight,
                control1.chaseHeight,
                control2.chaseHeight,
                p2.chaseHeight,
                t
            ),
            chaseStiffness = DoubleInterpolator.bezier(
                p1.chaseStiffness, control1.chaseStiffness, control2.chaseStiffness, p2.chaseStiffness, t
            ),
            chaseDamping = DoubleInterpolator.bezier(
                p1.chaseDamping,
                control1.chaseDamping,
                control2.chaseDamping,
                p2.chaseDamping,
                t
            ),
            targetOffsetX = DoubleInterpolator.bezier(
                p1.targetOffsetX,
                control1.targetOffsetX,
                control2.targetOffsetX,
                p2.targetOffsetX,
                t
            ),
            targetOffsetY = DoubleInterpolator.bezier(
                p1.targetOffsetY,
                control1.targetOffsetY,
                control2.targetOffsetY,
                p2.targetOffsetY,
                t
            ),
            targetOffsetZ = DoubleInterpolator.bezier(
                p1.targetOffsetZ,
                control1.targetOffsetZ,
                control2.targetOffsetZ,
                p2.targetOffsetZ,
                t
            ),
        )
    }

    override fun tangentControl(previous: ViewState, current: ViewState, next: ViewState, scale: Double): ViewState {
        if (!sameIdentity(previous, current) || !sameIdentity(current, next)) return current
        return current.copy(
            orbitDistance = DoubleInterpolator.tangentControl(
                previous.orbitDistance,
                current.orbitDistance,
                next.orbitDistance,
                scale
            ),
            orbitPitch = DoubleInterpolator.tangentControl(
                previous.orbitPitch,
                current.orbitPitch,
                next.orbitPitch,
                scale
            ),
            orbitYawOffset = AngleInterpolator.tangentControl(
                previous.orbitYawOffset,
                current.orbitYawOffset,
                next.orbitYawOffset,
                scale
            ),
            orbitHeight = DoubleInterpolator.tangentControl(
                previous.orbitHeight,
                current.orbitHeight,
                next.orbitHeight,
                scale
            ),
            orbitDegreesPerSecond = DoubleInterpolator.tangentControl(
                previous.orbitDegreesPerSecond, current.orbitDegreesPerSecond, next.orbitDegreesPerSecond, scale
            ),
            followOffsetX = DoubleInterpolator.tangentControl(
                previous.followOffsetX,
                current.followOffsetX,
                next.followOffsetX,
                scale
            ),
            followOffsetY = DoubleInterpolator.tangentControl(
                previous.followOffsetY,
                current.followOffsetY,
                next.followOffsetY,
                scale
            ),
            followOffsetZ = DoubleInterpolator.tangentControl(
                previous.followOffsetZ,
                current.followOffsetZ,
                next.followOffsetZ,
                scale
            ),
            chaseDistance = DoubleInterpolator.tangentControl(
                previous.chaseDistance,
                current.chaseDistance,
                next.chaseDistance,
                scale
            ),
            chaseHeight = DoubleInterpolator.tangentControl(
                previous.chaseHeight,
                current.chaseHeight,
                next.chaseHeight,
                scale
            ),
            chaseStiffness = DoubleInterpolator.tangentControl(
                previous.chaseStiffness,
                current.chaseStiffness,
                next.chaseStiffness,
                scale
            ),
            chaseDamping = DoubleInterpolator.tangentControl(
                previous.chaseDamping,
                current.chaseDamping,
                next.chaseDamping,
                scale
            ),
            targetOffsetX = DoubleInterpolator.tangentControl(
                previous.targetOffsetX,
                current.targetOffsetX,
                next.targetOffsetX,
                scale
            ),
            targetOffsetY = DoubleInterpolator.tangentControl(
                previous.targetOffsetY,
                current.targetOffsetY,
                next.targetOffsetY,
                scale
            ),
            targetOffsetZ = DoubleInterpolator.tangentControl(
                previous.targetOffsetZ,
                current.targetOffsetZ,
                next.targetOffsetZ,
                scale
            ),
        )
    }
}
