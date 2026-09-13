package gg.sona.recast.editor.host

import gg.sona.recast.editor.ProjectSummary
import gg.sona.recast.editor.Segment
import java.nio.file.Path

interface ReplayControl {
    fun open(path: Path): Boolean
    fun close()
    fun isOpen(): Boolean

    fun lastError(): String?

    fun listRecordings(): List<Path>
    fun describe(path: Path): RecordingInfo?
    fun rename(path: Path, name: String): Path?
    fun compact(path: Path): Boolean = false

    fun currentPath(): Path?

    fun listProjects(): List<ProjectSummary>
    fun openProject(path: Path): Boolean
    fun createProject(name: String, segments: List<Segment>): Path?
    fun deleteProject(path: Path): Boolean

    fun rebuildSequence(): Boolean
    fun sequenceBuilding(): Boolean
}
