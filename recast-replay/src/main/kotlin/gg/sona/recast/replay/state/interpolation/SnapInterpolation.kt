package gg.sona.recast.replay.state.interpolation

import gg.sona.recast.replay.state.shadow.MotionHistory
import gg.sona.recast.replay.state.shadow.Pose

object SnapInterpolation : Interpolation {
    override fun poseAt(history: MotionHistory, nanos: Long): Pose? {
        val positionIndex = Interpolation.bracket(history.positions, nanos)
        val rotationIndex = Interpolation.bracket(history.rotations, nanos)
        if (positionIndex < 0 || rotationIndex < 0) return null
        val position = history.positions[positionIndex]
        val rotation = history.rotations[rotationIndex]
        return Pose(
            position.a,
            position.b,
            position.c,
            rotation.a.toFloat(),
            rotation.b.toFloat(),
            rotation.c.toFloat()
        )
    }
}


