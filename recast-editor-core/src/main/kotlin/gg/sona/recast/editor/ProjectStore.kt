package gg.sona.recast.editor

import gg.sona.recast.clip.export.ExportJob
import gg.sona.recast.clip.recording.combine.CombineJob
import gg.sona.recast.clip.recording.combine.RecordingCombiner
import gg.sona.recast.core.log.RecastLog
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class ProjectStore(val directory: Path) {
    private val logger = RecastLog.logger("recast.projects")

    fun list(): List<ProjectSummary> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(EXTENSION) }.toList()
        }.mapNotNull { path ->
            runCatching {
                val project = ProjectCodec.load(path)
                ProjectSummary(
                    path,
                    project.name,
                    project.segments.toList(),
                    Files.getLastModifiedTime(path).toMillis(),
                    project.camera.keyframeTimes().size,
                    project.clips.size,
                    project.markers.size
                )
            }.onFailure { logger.warn("Could not read project $path", it) }.getOrNull()
        }.sortedByDescending { it.modifiedEpochMillis }
    }

    fun pathFor(name: String): Path {
        val clean = name.trim().replace(Regex("[/:*?\"<>|]"), "_").ifEmpty { "project" }
        var candidate = directory.resolve("$clean$EXTENSION")
        var counter = 2
        while (Files.exists(candidate)) {
            candidate = directory.resolve("$clean-$counter$EXTENSION")
            counter++
        }
        return candidate
    }

    fun create(name: String, segments: List<Segment>, sessionId: UUID): EditorProject {
        val path = pathFor(name)
        val project = EditorProject(UUID.randomUUID(), name, segments.firstOrNull()?.gameplay ?: path, sessionId)
        project.segments += segments
        project.file = path
        project.dirty = true
        Files.createDirectories(directory)
        ProjectCodec.save(project, path)
        return project
    }

    fun stitchedPathFor(project: EditorProject): Path {
        val base = project.file?.fileName?.toString()?.removeSuffix(EXTENSION) ?: project.id.toString()
        return directory.resolve("$base.sequence.recast")
    }

    fun stitchJob(project: EditorProject, output: Path): ExportJob {
        val sources = project.segments.map { it.gameplay }
        val ranges =
            project.segments.map { segment -> if (segment.inNanos == 0L && segment.outNanos == 0L) null else segment.inNanos..(if (segment.outNanos > 0L) segment.outNanos else Long.MAX_VALUE) }
        return CombineJob(RecordingCombiner(), sources, output, ranges)
    }

    companion object {
        const val EXTENSION = ".rcproj"
    }
}
