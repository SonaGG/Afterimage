package gg.sona.afterimage.world.interpolation

import gg.sona.afterimage.world.MotionHistory
import gg.sona.afterimage.world.Pose

object LinearInterpolation : Interpolation {
    override fun poseAt(history: MotionHistory, nanos: Long): Pose? {
        val position = DoubleArray(3)
        val rotation = DoubleArray(3)
        if (!Interpolation.linear(history.positions, nanos, false, position)) return null
        if (!Interpolation.linear(history.rotations, nanos, true, rotation)) return null
        return Interpolation.pose(position, rotation)
    }
}
