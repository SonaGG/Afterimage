package gg.sona.afterimage.capture.recording

import java.nio.file.Path

data class RecordingTarget(val path: Path, val metadata: Map<String, String>)
