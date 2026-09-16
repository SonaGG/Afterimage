package gg.sona.afterimage.mc.common

class FootstepBlock(
    val isAir: Boolean,
    val isLiquid: Boolean,
    val isWater: Boolean,
    val isClimbable: Boolean,
    val isFenceLike: Boolean,
    val isSnowLayer: Boolean,
    val sound: String?,
    val volume: Float,
    val pitch: Float,
)