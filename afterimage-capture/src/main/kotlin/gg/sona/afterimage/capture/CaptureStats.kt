package gg.sona.afterimage.capture

data class CaptureStats(
    val packetsCaptured: Long,
    val packetsRecorded: Long,
    val bytesWritten: Long,
    val keyframes: Int,
    val overflowEvents: Long,
    val elapsedNanos: Long,
)