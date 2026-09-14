package gg.sona.afterimage.camera

import gg.sona.afterimage.camera.track.SegmentMode

class PathSegment(val fromNanos: Long, val toNanos: Long, val mode: SegmentMode, val points: List<PathPoint>)