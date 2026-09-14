package gg.sona.afterimage.editor

import gg.sona.afterimage.clip.export.ExportJob
import gg.sona.afterimage.clip.recording.combine.CombineJob
import gg.sona.afterimage.clip.recording.combine.RecordingCombiner
import gg.sona.afterimage.core.log.AfterimageLog
import gg.sona.afterimage.format.AfterimageFormat
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class ProjectStore(val directory: Path) {
    private val logger = AfterimageLog.logger("afterimage.projects")

    fun list(): List<ProjectSummary> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { stream ->
            stream.filter { isProject(it.fileName.toString()) }.toList()
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
        val base = project.file?.fileName?.toString()?.removeSuffix(EXTENSION)?.removeSuffix(LEGACY_EXTENSION) ?: project.id.toString()
        return directory.resolve("$base.sequence.${AfterimageFormat.EXTENSION}")
    }

    fun stitchJob(project: EditorProject, output: Path): ExportJob {
        val sources = project.segments.map { it.gameplay }
        val ranges =
            project.segments.map { segment -> if (segment.inNanos == 0L && segment.outNanos == 0L) null else segment.inNanos..(if (segment.outNanos > 0L) segment.outNanos else Long.MAX_VALUE) }
        return CombineJob(RecordingCombiner(), sources, output, ranges)
    }

    companion object {
        const val EXTENSION = ".aftproj"
        const val LEGACY_EXTENSION = ".rcproj"

        fun isProject(fileName: String): Boolean = fileName.endsWith(EXTENSION) || fileName.endsWith(LEGACY_EXTENSION)

        fun forRecording(directory: Path, recording: Path): Path {
            val base = recording.fileName.toString().substringBeforeLast('.')
            val current = directory.resolve(base + EXTENSION)
            if (Files.exists(current)) return current
            val legacy = directory.resolve(base + LEGACY_EXTENSION)
            return if (Files.exists(legacy)) legacy else current
        }
    }
}
