package gg.sona.recast.clip.recording.compact

class CompactResult(val inputBytes: Long, val outputBytes: Long, val packets: Long, val chunksDeduplicated: Long) {
    val ratio: Double get() = if (outputBytes == 0L) 0.0 else inputBytes.toDouble() / outputBytes
}
