package gg.sona.afterimage.editor

import gg.sona.afterimage.camera.CameraMode
import gg.sona.afterimage.camera.CameraPath
import gg.sona.afterimage.camera.Easing
import gg.sona.afterimage.camera.TrackingBodyPart
import gg.sona.afterimage.camera.track.Keyframe
import gg.sona.afterimage.camera.track.SegmentMode
import gg.sona.afterimage.camera.track.Track
import gg.sona.afterimage.clip.codec.CameraPathCodec
import gg.sona.afterimage.clip.codec.ClipCodec
import gg.sona.afterimage.clip.codec.EasingCodec
import gg.sona.afterimage.editor.look.LookSettings
import gg.sona.afterimage.net.PacketFormatException
import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.net.PacketWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.*

object ProjectCodec {
    private const val VERSION = 16
    private const val LEGACY_BLOCK_OVERRIDE_ORDINAL = 14
    private const val LEGACY_POSE_ORDINAL = 17
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'C'.code.toByte(), 'P'.code.toByte(), 'J'.code.toByte())

    fun encode(project: EditorProject): ByteArray {
        val writer = PacketWriter(4096)
        writer.writeBytes(MAGIC)
        writer.writeShort(VERSION)
        writer.writeUuid(project.id)
        writer.writeString(project.name)
        writer.writeString(project.recording.toString())
        writer.writeUuid(project.sessionId)
        writer.writeLong(project.createdAtEpochMillis)
        writer.writeLong(project.inPointNanos)
        writer.writeLong(project.outPointNanos)
        CameraPathCodec.write(writer, project.camera)
        writer.writeVarInt(project.clips.size)
        for (clip in project.clips) {
            val bytes = ClipCodec.encode(clip)
            writer.writeVarInt(bytes.size)
            writer.writeBytes(bytes)
        }
        writer.writeVarInt(project.markers.size)
        for ((id, nanos, label, color, kind) in project.markers) writer.writeUuid(id).writeLong(nanos)
            .writeString(label)
            .writeInt(color).writeByte(kind.ordinal)
        writer.writeVarInt(project.lanes.size)
        for ((kind, name, muted, locked) in project.lanes) writer.writeByte(kind.ordinal).writeString(name)
            .writeBoolean(
                muted
            )
            .writeBoolean(locked)
        writeValueTrack(writer, project.speed)
        writer.writeInt(project.aimTargetId ?: Int.MIN_VALUE)
        writer.writeVarInt(project.segments.size)
        for ((gameplay, inNanos, outNanos, lengthNanos) in project.segments) writer.writeString(gameplay.toString())
            .writeLong(
                inNanos
            )
            .writeLong(outNanos).writeLong(lengthNanos)
        writer.writeString(project.sequenceKey)
        writer.writeVarInt(ValueLane.entries.size - 1)
        for (lane in ValueLane.entries) {
            if (lane == ValueLane.SPEED) continue
            writer.writeByte(lane.ordinal)
            writeValueTrack(writer, project.valueTrack(lane))
        }
        writer.writeVarInt(project.views.keyframes.size)
        for ((timeNanos, view, easing, mode) in project.views.keyframes) {
            writer.writeLong(timeNanos).writeByte(view.mode.ordinal).writeInt(view.targetEntityId)
            writer.writeDouble(view.orbitDistance).writeDouble(view.orbitPitch).writeDouble(view.orbitYawOffset)
                .writeDouble(view.orbitHeight)
            writer.writeDouble(view.followOffsetX).writeDouble(view.followOffsetY).writeDouble(view.followOffsetZ)
            writer.writeDouble(view.chaseDistance).writeDouble(view.chaseHeight)
            EasingCodec.write(writer, easing)
            writer.writeByte(mode.ordinal)
            writer.writeDouble(view.orbitDegreesPerSecond).writeBoolean(view.followLookAtTarget)
            writer.writeDouble(view.chaseStiffness).writeDouble(view.chaseDamping)
            writer.writeByte(view.bodyPart.ordinal)
            writer.writeDouble(view.targetOffsetX).writeDouble(view.targetOffsetY).writeDouble(view.targetOffsetZ)
        }
        writer.writeVarInt(project.timelapses.size)
        for ((id, nanos, skipNanos) in project.timelapses) writer.writeUuid(id).writeLong(nanos).writeLong(skipNanos)
        writer.writeVarInt(project.moments.size)
        for ((id, nanos, endNanos, peakNanos, label, kind, score, origin, entityId) in project.moments) {
            writer.writeUuid(id).writeLong(nanos).writeLong(endNanos).writeLong(peakNanos)
            writer.writeString(label).writeByte(kind.ordinal).writeDouble(score)
            writer.writeByte(origin.ordinal).writeInt(entityId)
        }
        writeLook(writer, project.look)
        writer.writeVarInt(project.packs.keyframes.size)
        for ((timeNanos, state) in project.packs.keyframes) {
            writer.writeLong(timeNanos).writeVarInt(state.packs.size)
            for (name in state.packs) writer.writeString(name)
        }
        return writer.toByteArray()
    }

    fun decode(bytes: ByteArray, idOverride: UUID? = null, nameOverride: String? = null): EditorProject {
        val reader = PacketReader(bytes, 0, bytes.size)
        require(reader.readBytes(4).contentEquals(MAGIC)) { "not a afterimage project" }
        val version = reader.readUnsignedShort()
        require(version <= VERSION) { "project version $version is newer than supported" }
        val id = reader.readUuid().let { idOverride ?: it }
        val name = reader.readString().let { nameOverride ?: it }
        val recording = Paths.get(reader.readString())
        val sessionId = reader.readUuid()
        val created = reader.readLong()
        val project = EditorProject(id, name, recording, sessionId, created)
        project.inPointNanos = reader.readLong()
        project.outPointNanos = reader.readLong()
        val camera: CameraPath = CameraPathCodec.read(
            reader,
            if (version >= 12) CameraPathCodec.VERSION else CameraPathCodec.VERSION_LEGACY
        )
        CameraPath.copyInto(project.camera, camera)
        repeat(reader.readVarInt()) { project.clips += ClipCodec.decode(reader.readBytes(reader.readVarInt())) }
        repeat(reader.readVarInt()) {
            project.markers += TimelineMarker(
                reader.readUuid(),
                reader.readLong(),
                reader.readString(),
                reader.readInt(),
                if (version >= 9) MarkerKind.of(reader.readUnsignedByte()) else MarkerKind.NOTE
            )
        }
        val laneCount = reader.readVarInt()
        repeat(laneCount) {
            var ordinal = reader.readUnsignedByte()
            val name = reader.readString()
            val muted = reader.readBoolean()
            val locked = reader.readBoolean()
            if (version < 14) {
                if (ordinal == LEGACY_BLOCK_OVERRIDE_ORDINAL) return@repeat
                if (ordinal > LEGACY_BLOCK_OVERRIDE_ORDINAL) ordinal--
            }
            if (version < 16) {
                if (ordinal == LEGACY_POSE_ORDINAL) return@repeat
                if (ordinal > LEGACY_POSE_ORDINAL) ordinal--
            }
            val kind = LaneKind.entries[ordinal.coerceIn(0, LaneKind.entries.size - 1)]
            project.replaceLane(kind, LaneState(kind, name, muted, locked))
        }
        if (version >= 2) readValueTrack(reader, project.speed, version)
        if (version >= 3) {
            try {
                readTracks(reader, project, version)
            } catch (error: PacketFormatException) {
                if (version >= 4) throw error
            }
        }
        return project
    }

    private fun readTracks(reader: PacketReader, project: EditorProject, version: Int) {
        if (version >= 5) project.aimTargetId = reader.readInt().takeIf { it != Int.MIN_VALUE }
        if (version >= 6) {
            repeat(reader.readVarInt()) {
                project.segments += Segment(
                    Paths.get(reader.readString()),
                    reader.readLong(),
                    reader.readLong(),
                    reader.readLong()
                )
            }
            project.sequenceKey = reader.readString()
        }
        val trackCount = if (version >= 4) reader.readVarInt() else ValueLane.entries.size - 1
        repeat(trackCount) {
            val lane = ValueLane.entries[reader.readUnsignedByte().coerceIn(0, ValueLane.entries.size - 1)]
            readValueTrack(reader, project.valueTrack(lane), version)
        }
        repeat(reader.readVarInt()) {
            val time = reader.readLong()
            val mode = CameraMode.entries[reader.readUnsignedByte().coerceIn(0, CameraMode.entries.size - 1)]
            val target = reader.readInt()
            val orbitDistance = reader.readDouble()
            val orbitPitch = reader.readDouble()
            val orbitYawOffset = reader.readDouble()
            val orbitHeight = reader.readDouble()
            val followOffsetX = reader.readDouble()
            val followOffsetY = reader.readDouble()
            val followOffsetZ = reader.readDouble()
            val chaseDistance = reader.readDouble()
            val chaseHeight = reader.readDouble()
            var easing = Easing.LINEAR
            var segmentMode = SegmentMode.HOLD
            var orbitDegreesPerSecond = 0.0
            var followLookAtTarget = true
            var chaseStiffness = 30.0
            var chaseDamping = 8.0
            var bodyPart = TrackingBodyPart.HEAD
            var targetOffsetX = 0.0
            var targetOffsetY = 0.0
            var targetOffsetZ = 0.0
            if (version >= 7) {
                easing = EasingCodec.read(reader, legacy = version < 12)
                segmentMode = SegmentMode.entries[reader.readUnsignedByte().coerceIn(0, SegmentMode.entries.size - 1)]
                orbitDegreesPerSecond = reader.readDouble()
                followLookAtTarget = reader.readBoolean()
                chaseStiffness = reader.readDouble()
                chaseDamping = reader.readDouble()
                bodyPart =
                    TrackingBodyPart.entries[reader.readUnsignedByte().coerceIn(0, TrackingBodyPart.entries.size - 1)]
                targetOffsetX = reader.readDouble()
                targetOffsetY = reader.readDouble()
                targetOffsetZ = reader.readDouble()
            }
            val view = ViewState(
                mode,
                target,
                orbitDistance,
                orbitPitch,
                orbitYawOffset,
                orbitHeight,
                followOffsetX,
                followOffsetY,
                followOffsetZ,
                chaseDistance,
                chaseHeight,
                orbitDegreesPerSecond,
                followLookAtTarget,
                chaseStiffness,
                chaseDamping,
                bodyPart,
                targetOffsetX,
                targetOffsetY,
                targetOffsetZ,
            )
            project.views.set(Keyframe(time, view, easing, segmentMode))
        }
        if (version >= 8) {
            repeat(reader.readVarInt()) {
                project.timelapses += TimelapseMark(reader.readUuid(), reader.readLong(), reader.readLong())
            }
            if (version < 14) {
                repeat(reader.readVarInt()) {
                    reader.readLong()
                    reader.readUuid()
                    repeat(reader.readVarInt()) {
                        reader.readLong()
                        reader.readVarInt()
                    }
                }
            }
        }
        if (version >= 9) {
            repeat(reader.readVarInt()) {
                project.moments += Moment(
                    reader.readUuid(),
                    reader.readLong(),
                    reader.readLong(),
                    reader.readLong(),
                    reader.readString(),
                    MomentKind.of(reader.readUnsignedByte()),
                    reader.readDouble(),
                    MomentOrigin.entries[reader.readUnsignedByte().coerceIn(0, 1)],
                    reader.readInt(),
                )
            }
        }
        if (version in 13..15) {
            repeat(reader.readVarInt()) {
                reader.readInt()
                repeat(reader.readVarInt()) {
                    reader.readLong()
                    EasingCodec.read(reader, legacy = false)
                    reader.readUnsignedByte()
                    repeat(reader.readVarInt()) {
                        reader.readUnsignedByte()
                        repeat(4) { reader.readFloat() }
                    }
                }
            }
        }
        if (version >= 15) {
            readLook(reader, project.look)
            repeat(reader.readVarInt()) {
                val time = reader.readLong()
                val names = ArrayList<String>()
                repeat(reader.readVarInt()) { names += reader.readString() }
                project.packs.set(Keyframe(time, PackState(names), Easing.LINEAR, SegmentMode.HOLD))
            }
        }
    }

    private fun writeLook(writer: PacketWriter, look: LookSettings) {
        writer.writeBoolean(look.depthOfField)
        writer.writeInt(look.focusTargetId ?: Int.MIN_VALUE)
        writer.writeDouble(look.focusDistance).writeDouble(look.aperture).writeDouble(look.focusRange)
        writer.writeDouble(look.exposure).writeDouble(look.contrast).writeDouble(look.saturation)
        writer.writeString(look.lut).writeDouble(look.lutStrength)
        writer.writeDouble(look.vignette).writeDouble(look.vignetteSoftness)
        writer.writeDouble(look.letterbox)
        writer.writeDouble(look.grain).writeDouble(look.grainSize)
    }

    private fun readLook(reader: PacketReader, look: LookSettings) {
        look.depthOfField = reader.readBoolean()
        look.focusTargetId = reader.readInt().takeIf { it != Int.MIN_VALUE }
        look.focusDistance = reader.readDouble()
        look.aperture = reader.readDouble()
        look.focusRange = reader.readDouble()
        look.exposure = reader.readDouble()
        look.contrast = reader.readDouble()
        look.saturation = reader.readDouble()
        look.lut = reader.readString()
        look.lutStrength = reader.readDouble()
        look.vignette = reader.readDouble()
        look.vignetteSoftness = reader.readDouble()
        look.letterbox = reader.readDouble()
        look.grain = reader.readDouble()
        look.grainSize = reader.readDouble()
    }

    private fun writeValueTrack(writer: PacketWriter, track: Track<Double>) {
        writer.writeVarInt(track.keyframes.size)
        for ((timeNanos, value, easing, mode, handleIn, handleOut) in track.keyframes) {
            writer.writeLong(timeNanos).writeDouble(value)
            EasingCodec.write(writer, easing)
            writer.writeByte(mode.ordinal)
            writer.writeBoolean(handleIn != null)
            handleIn?.let { writer.writeDouble(it) }
            writer.writeBoolean(handleOut != null)
            handleOut?.let { writer.writeDouble(it) }
        }
        EasingCodec.writeExtrapolation(writer, track.preExtrapolation, track.postExtrapolation)
    }

    private fun readValueTrack(reader: PacketReader, track: Track<Double>, version: Int) {
        repeat(reader.readVarInt()) {
            val time = reader.readLong()
            val value = reader.readDouble()
            val easing = EasingCodec.read(reader, legacy = version < 12)
            val mode = SegmentMode.entries[reader.readUnsignedByte().coerceIn(0, SegmentMode.entries.size - 1)]
            var handleIn: Double? = null
            var handleOut: Double? = null
            if (version >= 12) {
                if (reader.readBoolean()) handleIn = reader.readDouble()
                if (reader.readBoolean()) handleOut = reader.readDouble()
            }
            track.set(Keyframe(time, value, easing, mode, handleIn, handleOut))
        }
        if (version >= 12) {
            val (pre, post) = EasingCodec.readExtrapolation(reader)
            track.preExtrapolation = pre
            track.postExtrapolation = post
        }
    }

    fun save(project: EditorProject, path: Path) {
        Files.createDirectories(path.toAbsolutePath().parent)
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(temporary, encode(project))
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        project.file = path
        project.dirty = false
    }

    fun load(path: Path): EditorProject = decode(Files.readAllBytes(path)).also { it.file = path }

    fun cut(project: EditorProject, name: String, path: Path): EditorProject {
        val copy = decode(encode(project), UUID.randomUUID(), name)
        save(copy, path)
        return copy
    }
}
