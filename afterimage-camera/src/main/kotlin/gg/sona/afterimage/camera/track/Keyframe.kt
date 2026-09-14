package gg.sona.afterimage.camera.track

import gg.sona.afterimage.camera.Easing

data class Keyframe<T>(
    val timeNanos: Long,
    val value: T,
    val easing: Easing = Easing.LINEAR,
    val mode: SegmentMode = SegmentMode.CATMULL_ROM,
    val handleIn: T? = null,
    val handleOut: T? = null,
)
