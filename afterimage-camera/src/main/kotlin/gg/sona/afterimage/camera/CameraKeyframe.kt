package gg.sona.afterimage.camera

import gg.sona.afterimage.camera.track.SegmentMode

data class CameraKeyframe(val timeNanos: Long, val pose: CameraPose, val mode: SegmentMode, val easing: Easing)
