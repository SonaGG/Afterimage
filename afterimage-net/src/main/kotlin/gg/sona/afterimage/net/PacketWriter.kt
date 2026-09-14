package gg.sona.afterimage.net

import gg.sona.afterimage.net.nbt.NbtCodec
import gg.sona.afterimage.net.nbt.NbtCompound
import java.util.*

class PacketWriter(initialCapacity: Int = 256) {

    private var buffer = ByteArray(initialCapacity.coerceAtLeast(16))

    var size: Int = 0
        private set

    fun reset() {
        size = 0
    }

    fun ensure(extra: Int) {
        val needed = size + extra
        if (needed > buffer.size) {
            var capacity = buffer.size shl 1
            while (capacity < needed) capacity = capacity shl 1
            buffer = buffer.copyOf(capacity)
        }
    }

    fun writeByte(value: Int): PacketWriter {
        ensure(1)
        buffer[size++] = value.toByte()
        return this
    }

    fun writeBoolean(value: Boolean): PacketWriter = writeByte(if (value) 1 else 0)

    fun writeShort(value: Int): PacketWriter {
        ensure(2)
        buffer[size++] = (value shr 8).toByte()
        buffer[size++] = value.toByte()
        return this
    }

    fun writeInt(value: Int): PacketWriter {
        ensure(4)
        buffer[size++] = (value shr 24).toByte()
        buffer[size++] = (value shr 16).toByte()
        buffer[size++] = (value shr 8).toByte()
        buffer[size++] = value.toByte()
        return this
    }

    fun writeLong(value: Long): PacketWriter {
        writeInt((value shr 32).toInt())
        writeInt(value.toInt())
        return this
    }

    fun writeFloat(value: Float): PacketWriter = writeInt(value.toRawBits())

    fun writeDouble(value: Double): PacketWriter = writeLong(value.toRawBits())

    fun writeVarInt(value: Int): PacketWriter {
        ensure(VarInts.MAX_VARINT_BYTES)
        size += VarInts.write(buffer, size, value)
        return this
    }

    fun writeVarLong(value: Long): PacketWriter {
        var remaining = value
        ensure(VarInts.MAX_VARLONG_BYTES)
        while (remaining and -0x80L != 0L) {
            buffer[size++] = ((remaining and 0x7FL) or 0x80L).toByte()
            remaining = remaining ushr 7
        }
        buffer[size++] = remaining.toByte()
        return this
    }

    fun writeString(value: String): PacketWriter {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarInt(bytes.size)
        return writeBytes(bytes)
    }

    fun writeUtf(value: String): PacketWriter {
        val bytes = ModifiedUtf8.encode(value)
        writeShort(bytes.size)
        return writeBytes(bytes)
    }

    fun writeUuid(value: UUID): PacketWriter {
        writeLong(value.mostSignificantBits)
        return writeLong(value.leastSignificantBits)
    }

    fun writePosition(packed: Long): PacketWriter = writeLong(packed)

    fun writeBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): PacketWriter {
        ensure(length)
        System.arraycopy(bytes, offset, buffer, size, length)
        size += length
        return this
    }

    fun writeNbt(compound: NbtCompound?): PacketWriter {
        NbtCodec.writeNetwork(this, compound)
        return this
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    fun rawBuffer(): ByteArray = buffer

    fun setByte(index: Int, value: Int) {
        buffer[index] = value.toByte()
    }
}
