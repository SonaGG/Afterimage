package gg.sona.afterimage.clip.codec

import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.clip.ClipOrigin
import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.net.PacketWriter
import java.nio.file.Path
import java.nio.file.Paths

object ClipCodec {
    private const val VERSION = 2
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'C'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte())

    fun encode(clip: Clip): ByteArray {
        val writer = PacketWriter(512)
        writer.writeBytes(MAGIC)
        writer.writeShort(VERSION)
        writer.writeUuid(clip.id)
        writer.writeString(clip.recording.toString())
        writer.writeUuid(clip.sessionId)
        writer.writeLong(clip.startNanos)
        writer.writeLong(clip.endNanos)
        writer.writeString(clip.title)
        writer.writeVarInt(clip.tags.size)
        for (tag in clip.tags) writer.writeString(tag)
        writer.writeByte(clip.origin.ordinal)
        writer.writeLong(clip.createdAtEpochMillis)
        writer.writeString(clip.note)
        val camera = clip.cameraOverride
        writer.writeBoolean(camera != null)
        if (camera != null) CameraPathCodec.write(writer, camera)
        return writer.toByteArray()
    }

    fun decode(bytes: ByteArray): Clip {
        val reader = PacketReader(bytes, 0, bytes.size)
        require(reader.readBytes(4).contentEquals(MAGIC)) { "not a afterimage clip" }
        val version = reader.readUnsignedShort()
        require(version <= VERSION) { "clip version $version is newer than supported" }
        val id = reader.readUuid()
        val recording: Path = Paths.get(reader.readString())
        val sessionId = reader.readUuid()
        val start = reader.readLong()
        val end = reader.readLong()
        val title = reader.readString()
        val tags = LinkedHashSet<String>()
        repeat(reader.readVarInt()) { tags += reader.readString() }
        val origin = ClipOrigin.entries[reader.readUnsignedByte().coerceIn(0, ClipOrigin.entries.size - 1)]
        val created = reader.readLong()
        val note = reader.readString()
        val camera = if (reader.readBoolean()) CameraPathCodec.read(
            reader,
            if (version >= 2) CameraPathCodec.VERSION else CameraPathCodec.VERSION_LEGACY
        ) else null
        return Clip(id, recording, sessionId, start, end, title, tags, origin, created, camera, note)
    }
}

