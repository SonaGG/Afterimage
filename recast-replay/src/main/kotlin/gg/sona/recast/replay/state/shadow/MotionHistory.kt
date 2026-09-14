package gg.sona.recast.replay.state.shadow

class MotionHistory(capacity: Int = 16) {
    val positions = SampleTrack(capacity)
    val rotations = SampleTrack(capacity)

    fun clear() {
        positions.clear()
        rotations.clear()
    }

    fun copyFrom(other: MotionHistory) {
        positions.copyFrom(other.positions)
        rotations.copyFrom(other.rotations)
    }
}
