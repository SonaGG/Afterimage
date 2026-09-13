package gg.sona.recast.editor

import java.nio.file.Path

data class Segment(val gameplay: Path, val inNanos: Long = 0L, val outNanos: Long = 0L, val lengthNanos: Long = 0L) {
    val key: String get() = "${gameplay.toAbsolutePath()}|$inNanos|$outNanos"
}
