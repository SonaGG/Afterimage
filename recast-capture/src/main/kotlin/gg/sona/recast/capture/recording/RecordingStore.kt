package gg.sona.recast.capture.recording

import gg.sona.recast.core.log.RecastLog
import gg.sona.recast.format.RecastFormat
import gg.sona.recast.format.RecastReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

class RecordingStore(val directory: Path) {
    private val logger = RecastLog.logger("recast.store")

    fun newTarget(metadata: Map<String, String>, nowMillis: Long = System.currentTimeMillis()): RecordingTarget {
        Files.createDirectories(directory)
        val base = FILE_NAME.format(Instant.ofEpochMilli(nowMillis))
        var candidate = directory.resolve("$base.${RecastFormat.EXTENSION}")
        var suffix = 1
        while (Files.exists(candidate)) candidate = directory.resolve("$base-${suffix++}.${RecastFormat.EXTENSION}")
        return RecordingTarget(candidate, metadata)
    }

    fun list(): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return directory.listDirectoryEntries()
            .filter { it.isRegularFile() && it.extension == RecastFormat.EXTENSION }
            .sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
    }

    fun repairAll(): Int {
        var repaired = 0
        for (path in list()) {
            try {
                if (RecastReader.repair(path)) {
                    repaired++
                    logger.info("Repaired unfinished recording ${path.fileName}")
                }
            } catch (error: Throwable) {
                logger.warn("Could not repair ${path.fileName}", error)
            }
        }
        return repaired
    }

    private companion object {
        val FILE_NAME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault())
    }
}
