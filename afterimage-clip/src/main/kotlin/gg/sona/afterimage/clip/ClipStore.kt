package gg.sona.afterimage.clip

import gg.sona.afterimage.clip.codec.ClipCodec
import gg.sona.afterimage.core.log.AfterimageLog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

class ClipStore(val directory: Path) {
    private val logger = AfterimageLog.logger("afterimage.clips")

    fun save(clip: Clip): Path {
        Files.createDirectories(directory)
        val target = pathOf(clip.id)
        val temporary = directory.resolve("${clip.id}.$EXTENSION.tmp")
        Files.write(temporary, ClipCodec.encode(clip))
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    fun load(id: UUID): Clip? {
        val path = pathOf(id)
        if (!Files.exists(path)) return null
        return runCatching { ClipCodec.decode(Files.readAllBytes(path)) }
            .onFailure { logger.warn("Could not read clip $id", it) }
            .getOrNull()
    }

    fun delete(id: UUID): Boolean = Files.deleteIfExists(pathOf(id))

    fun list(): List<Clip> {
        if (!Files.isDirectory(directory)) return emptyList()
        return directory.listDirectoryEntries()
            .filter { it.isRegularFile() && it.extension == EXTENSION }
            .mapNotNull { path ->
                runCatching { ClipCodec.decode(Files.readAllBytes(path)) }.onFailure {
                    logger.warn(
                        "Skipping unreadable clip ${path.fileName}",
                        it
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.createdAtEpochMillis }
    }

    fun forRecording(recording: Path): List<Clip> = list().filter { it.recording == recording }

    private fun pathOf(id: UUID): Path = directory.resolve("$id.$EXTENSION")

    companion object {
        const val EXTENSION = "clip"
    }
}
