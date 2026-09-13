package gg.sona.recast.clip.bake

import java.nio.file.Path

data class BakeResult(val path: Path, val packets: Long, val keyframes: Int, val durationNanos: Long)
