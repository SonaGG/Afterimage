package gg.sona.afterimage.index

import com.github.luben.zstd.Zstd
import gg.sona.afterimage.replay.state.shadow.EntityKind
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*

object IndexCodec {
    private const val MAGIC = 0x52434958 // RCIX
    private const val LEVEL = 6

    fun write(path: Path, key: IndexKey, index: ReplayIndex) {
        val raw = ByteArrayOutputStream(1 shl 20)
        DataOutputStream(raw).use { out -> writeBody(out, index) }
        val body = raw.toByteArray()
        val compressed = Zstd.compress(body, LEVEL)
        val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
        DataOutputStream(Files.newOutputStream(temp).buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(ReplayIndex.VERSION)
            key.write(out)
            out.writeInt(body.size)
            out.writeInt(compressed.size)
            out.write(compressed)
        }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun read(path: Path, key: IndexKey): ReplayIndex? {
        if (!Files.isRegularFile(path)) return null
        return try {
            DataInputStream(Files.newInputStream(path).buffered()).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() != ReplayIndex.VERSION) return null
                if (IndexKey.read(input) != key) return null
                val rawLength = input.readInt()
                val compressedLength = input.readInt()
                val compressed = ByteArray(compressedLength)
                input.readFully(compressed)
                val body = Zstd.decompress(compressed, rawLength)
                DataInputStream(ByteArrayInputStream(body)).use { readBody(it) }
            }
        } catch (error: IOException) {
            null
        } catch (error: RuntimeException) {
            null
        }
    }

    private fun writeBody(out: DataOutputStream, index: ReplayIndex) {
        out.writeLong(index.startNanos)
        out.writeLong(index.endNanos)
        out.writeLong(index.tickNanos)
        out.writeInt(index.tickCount)
        ints(out, index.dimensions)
        out.writeInt(index.tracks.size)
        for (track in index.tracks) {
            out.writeInt(track.entityId)
            out.writeByte(track.kind.ordinal)
            out.writeInt(track.type)
            uuid(out, track.uuid)
            string(out, track.name)
            out.writeBoolean(track.isRecorder)
            out.writeInt(track.shooterId)
            out.writeInt(track.firstTick)
            out.writeInt(track.lastTick)
            doubles(out, track.x)
            doubles(out, track.y)
            doubles(out, track.z)
            floats(out, track.yaw)
            floats(out, track.pitch)
            floats(out, track.headYaw)
            floats(out, track.health)
            shorts(out, track.held)
            for (slot in track.armor) shorts(out, slot)
            bytes(out, track.flags)
            ints(out, track.vehicle)
        }
        val events = index.events
        out.writeInt(events.size)
        longs(out, events.nanos)
        ints(out, events.tick)
        bytes(out, events.kind)
        ints(out, events.a)
        ints(out, events.b)
        doubles(out, events.x)
        doubles(out, events.y)
        doubles(out, events.z)
        floats(out, events.value)
        for (text in events.text) string(out, text)
        val blocks = index.blocks
        out.writeInt(blocks.size)
        longs(out, blocks.nanos)
        ints(out, blocks.tick)
        ints(out, blocks.x)
        ints(out, blocks.y)
        ints(out, blocks.z)
        ints(out, blocks.from)
        ints(out, blocks.to)
        ints(out, blocks.by)
    }

    private fun readBody(input: DataInputStream): ReplayIndex {
        val startNanos = input.readLong()
        val endNanos = input.readLong()
        val tickNanos = input.readLong()
        val tickCount = input.readInt()
        val dimensions = ints(input)
        val trackCount = input.readInt()
        val tracks = ArrayList<EntityTrack>(trackCount)
        val kinds = EntityKind.entries.toTypedArray()
        repeat(trackCount) {
            tracks += EntityTrack(
                input.readInt(),
                kinds[input.readUnsignedByte()],
                input.readInt(),
                uuid(input),
                string(input),
                input.readBoolean(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                doubles(input),
                doubles(input),
                doubles(input),
                floats(input),
                floats(input),
                floats(input),
                floats(input),
                shorts(input),
                Array(4) { shorts(input) },
                bytes(input),
                ints(input),
            )
        }
        val eventCount = input.readInt()
        val events = EventTable(
            longs(input), ints(input), bytes(input), ints(input), ints(input),
            doubles(input), doubles(input), doubles(input), floats(input),
            Array(eventCount) { string(input) }
        )
        input.readInt()
        val blocks = BlockChangeTable(
            longs(input), ints(input), ints(input), ints(input), ints(input), ints(input), ints(input), ints(input)
        )
        return ReplayIndex(startNanos, endNanos, tickNanos, tickCount, tracks, events, blocks, dimensions)
    }

    private fun string(out: DataOutputStream, value: String?) {
        if (value == null) {
            out.writeInt(-1)
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun string(input: DataInputStream): String? {
        val length = input.readInt()
        if (length < 0) return null
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun uuid(out: DataOutputStream, value: UUID?) {
        out.writeBoolean(value != null)
        if (value != null) {
            out.writeLong(value.mostSignificantBits)
            out.writeLong(value.leastSignificantBits)
        }
    }

    private fun uuid(input: DataInputStream): UUID? =
        if (input.readBoolean()) UUID(input.readLong(), input.readLong()) else null

    private fun doubles(out: DataOutputStream, values: DoubleArray) {
        out.writeInt(values.size)
        for (value in values) out.writeDouble(value)
    }

    private fun doubles(input: DataInputStream): DoubleArray = DoubleArray(input.readInt()) { input.readDouble() }

    private fun floats(out: DataOutputStream, values: FloatArray) {
        out.writeInt(values.size)
        for (value in values) out.writeFloat(value)
    }

    private fun floats(input: DataInputStream): FloatArray = FloatArray(input.readInt()) { input.readFloat() }

    private fun longs(out: DataOutputStream, values: LongArray) {
        out.writeInt(values.size)
        for (value in values) out.writeLong(value)
    }

    private fun longs(input: DataInputStream): LongArray = LongArray(input.readInt()) { input.readLong() }

    private fun ints(out: DataOutputStream, values: IntArray) {
        out.writeInt(values.size)
        for (value in values) out.writeInt(value)
    }

    private fun ints(input: DataInputStream): IntArray = IntArray(input.readInt()) { input.readInt() }

    private fun shorts(out: DataOutputStream, values: ShortArray) {
        out.writeInt(values.size)
        for (value in values) out.writeShort(value.toInt())
    }

    private fun shorts(input: DataInputStream): ShortArray = ShortArray(input.readInt()) { input.readShort() }

    private fun bytes(out: DataOutputStream, values: ByteArray) {
        out.writeInt(values.size)
        out.write(values)
    }

    private fun bytes(input: DataInputStream): ByteArray {
        val values = ByteArray(input.readInt())
        input.readFully(values)
        return values
    }
}
