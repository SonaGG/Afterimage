package gg.sona.recast.replay.state.interpolation

import gg.sona.recast.replay.state.shadow.MotionHistory
import gg.sona.recast.replay.state.shadow.Pose

object LinearInterpolation : Interpolation {
    override fun poseAt(history: MotionHistory, nanos: Long): Pose? {
        val position = DoubleArray(3)
        val rotation = DoubleArray(3)
        if (!Interpolation.linear(history.positions, nanos, false, position)) return null
        if (!Interpolation.linear(history.rotations, nanos, true, rotation)) return null
        return Interpolation.pose(position, rotation)
    }
}
