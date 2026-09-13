package gg.sona.recast.camera

import gg.sona.recast.camera.track.SegmentMode

data class CameraKeyframe(val timeNanos: Long, val pose: CameraPose, val mode: SegmentMode, val easing: Easing)
