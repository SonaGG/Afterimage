package gg.sona.recast.format

import java.util.*

data class RecordingHeader(
    val sessionId: UUID,
    val protocolVersion: Int,
    val startEpochMillis: Long,
    val keyframeIntervalNanos: Long,
    val metadata: Map<String, String>,
) {
    fun metadata(key: String): String? = metadata[key]
}
