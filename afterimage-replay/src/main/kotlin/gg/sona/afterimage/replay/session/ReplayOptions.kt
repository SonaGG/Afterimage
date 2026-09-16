package gg.sona.afterimage.replay.session

data class ReplayOptions(
    val keyframeJumpThresholdNanos: Long = -1L,
    val loop: Boolean = false,
    val settleNanos: Long = 0L,
)
