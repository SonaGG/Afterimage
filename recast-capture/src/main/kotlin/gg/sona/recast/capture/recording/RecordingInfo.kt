package gg.sona.recast.capture.recording

import java.nio.file.Path
import java.util.*

data class RecordingInfo(val path: Path, val sessionId: UUID, val originNanos: Long)