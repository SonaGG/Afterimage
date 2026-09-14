package gg.sona.afterimage.clip.codec

import gg.sona.afterimage.camera.CameraPath
import gg.sona.afterimage.camera.Rotation
import gg.sona.afterimage.camera.track.Keyframe
import gg.sona.afterimage.camera.track.SegmentMode
import gg.sona.afterimage.camera.track.Track
import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.net.PacketWriter
import org.joml.Vector3d

object CameraPathCodec {
    const val VERSION_LEGACY = 1
    const val VERSION = 2

    fun write(writer: PacketWriter, path: CameraPath) {
        writer.writeString(path.name)
        writeTrack(writer, path.position) { writeVector(writer, it) }
        writeTrack(writer, path.rotation) { writeRotation(writer, it) }
        writeTrack(writer, path.fov) { writer.writeDouble(it) }
    }

    fun read(reader: PacketReader, version: Int = VERSION): CameraPath {
        val path = CameraPath(reader.readString())
        readTrack(reader, path.position, version) { readVector(reader) }
        readTrack(reader, path.rotation, version) { readRotation(reader) }
        readTrack(reader, path.fov, version) { reader.readDouble() }
        return path
    }

    private fun <T> writeTrack(writer: PacketWriter, track: Track<T>, writeValue: (T) -> Unit) {
        writer.writeVarInt(track.keyframes.size)
        for (frame in track.keyframes) {
            writer.writeLong(frame.timeNanos)
            writeValue(frame.value)
            EasingCodec.write(writer, frame.easing)
            writer.writeByte(frame.mode.ordinal)
            writer.writeBoolean(frame.handleIn != null)
            frame.handleIn?.let(writeValue)
            writer.writeBoolean(frame.handleOut != null)
            frame.handleOut?.let(writeValue)
        }
        EasingCodec.writeExtrapolation(writer, track.preExtrapolation, track.postExtrapolation)
    }

    private fun <T> readTrack(reader: PacketReader, track: Track<T>, version: Int, readValue: () -> T) {
        repeat(reader.readVarInt()) {
            val time = reader.readLong()
            val value = readValue()
            val easing = EasingCodec.read(reader, legacy = version < VERSION)
            val mode = SegmentMode.entries[reader.readUnsignedByte().coerceIn(0, SegmentMode.entries.size - 1)]
            val handleIn = if (reader.readBoolean()) readValue() else null
            val handleOut = if (reader.readBoolean()) readValue() else null
            track.set(Keyframe(time, value, easing, mode, handleIn, handleOut))
        }
        if (version >= VERSION) {
            val (pre, post) = EasingCodec.readExtrapolation(reader)
            track.preExtrapolation = pre
            track.postExtrapolation = post
        }
    }

    private fun writeVector(writer: PacketWriter, value: Vector3d) {
        writer.writeDouble(value.x).writeDouble(value.y).writeDouble(value.z)
    }

    private fun readVector(reader: PacketReader): Vector3d =
        Vector3d(reader.readDouble(), reader.readDouble(), reader.readDouble())

    private fun writeRotation(writer: PacketWriter, value: Rotation) {
        writer.writeDouble(value.yaw).writeDouble(value.pitch).writeDouble(value.roll)
    }

    private fun readRotation(reader: PacketReader): Rotation =
        Rotation(reader.readDouble(), reader.readDouble(), reader.readDouble())
}
