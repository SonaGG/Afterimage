package gg.sona.recast.camera

import gg.sona.recast.camera.track.SegmentMode

class PathSegment(val fromNanos: Long, val toNanos: Long, val mode: SegmentMode, val points: List<PathPoint>)