package gg.sona.recast.capture

import java.nio.file.Path

interface CaptureListener {
    fun onRecordingStarted(path: Path) {}
    fun onKeyframe(recordingNanos: Long, packets: Int) {}
    fun onRecordingStopped(path: Path, stats: CaptureStats) {}
    fun onFailure(stage: String, error: Throwable) {}
}