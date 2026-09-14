package gg.sona.afterimage.editor

import gg.sona.afterimage.camera.CameraPath
import gg.sona.afterimage.clip.codec.CameraPathCodec
import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.net.PacketWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

class PathPresets(private val directory: Path) {
    fun list(): List<String> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(EXTENSION) }
                .map { it.fileName.toString().removeSuffix(EXTENSION) }
                .sorted()
                .collect(Collectors.toList())
        }
    }

    fun save(name: String, path: CameraPath) {
        Files.createDirectories(directory)
        val writer = PacketWriter(1024)
        writer.writeBytes(MAGIC)
        writer.writeShort(CameraPathCodec.VERSION)
        CameraPathCodec.write(writer, path)
        Files.write(directory.resolve(safeName(name) + EXTENSION), writer.toByteArray())
    }

    fun load(name: String): CameraPath? {
        val file = directory.resolve(safeName(name) + EXTENSION)
        if (!Files.exists(file)) return null
        val bytes = Files.readAllBytes(file)
        val reader = PacketReader(bytes, 0, bytes.size)
        val magic = reader.readBytes(MAGIC.size)
        if (magic.contentEquals(LEGACY_MAGIC)) return CameraPathCodec.read(reader, CameraPathCodec.VERSION_LEGACY)
        if (!magic.contentEquals(MAGIC)) return null
        val version = reader.readUnsignedShort()
        if (version > CameraPathCodec.VERSION) return null
        return CameraPathCodec.read(reader, version)
    }

    fun delete(name: String): Boolean = Files.deleteIfExists(directory.resolve(safeName(name) + EXTENSION))

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9-_ .]"), "_").trim().ifEmpty { "preset" }

    private companion object {
        const val EXTENSION = ".aftpath"
        val LEGACY_MAGIC = byteArrayOf('R'.code.toByte(), 'C'.code.toByte(), 'P'.code.toByte(), 'H'.code.toByte())
        val MAGIC = byteArrayOf('R'.code.toByte(), 'C'.code.toByte(), 'P'.code.toByte(), 'V'.code.toByte())
    }
}
