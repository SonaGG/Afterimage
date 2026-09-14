package gg.sona.afterimage.net

import gg.sona.afterimage.net.nbt.NbtCodec
import gg.sona.afterimage.net.nbt.NbtCompound
import java.util.*

class PacketReader(val array: ByteArray, val start: Int, val limit: Int) {

    var position: Int = start

    val remaining: Int get() = limit - position

    fun hasRemaining(): Boolean = position < limit

    fun require(bytes: Int) {
        if (remaining < bytes) throw PacketFormatException("needed $bytes bytes but only $remaining remain at $position")
    }

    fun skip(bytes: Int) {
        require(bytes)
        position += bytes
    }

    fun readByte(): Byte {
        require(1)
        return array[position++]
    }

    fun readUnsignedByte(): Int = readByte().toInt() and 0xFF

    fun readBoolean(): Boolean = readByte().toInt() != 0

    fun readShort(): Short {
        require(2)
        val value = ((array[position].toInt() and 0xFF) shl 8) or (array[position + 1].toInt() and 0xFF)
        position += 2
        return value.toShort()
    }

    fun readUnsignedShort(): Int = readShort().toInt() and 0xFFFF

    fun readInt(): Int {
        require(4)
        val value = ((array[position].toInt() and 0xFF) shl 24) or
                ((array[position + 1].toInt() and 0xFF) shl 16) or
                ((array[position + 2].toInt() and 0xFF) shl 8) or
                (array[position + 3].toInt() and 0xFF)
        position += 4
        return value
    }

    fun readLong(): Long {
        val high = readInt().toLong() and 0xFFFFFFFFL
        val low = readInt().toLong() and 0xFFFFFFFFL
        return (high shl 32) or low
    }

    fun readFloat(): Float = Float.fromBits(readInt())

    fun readDouble(): Double = Double.fromBits(readLong())

    fun readVarInt(): Int {
        val packed = VarInts.read(array, position, limit)
        position += VarInts.length(packed)
        return VarInts.value(packed)
    }

    fun readVarLong(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = readByte().toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
            if (shift > 70) throw PacketFormatException("varlong too long")
        }
    }

    fun readString(maxLength: Int = 32767): String {
        val length = readVarInt()
        if (length < 0 || length > maxLength * 4) throw PacketFormatException("string length $length out of range")
        require(length)
        val value = String(array, position, length, Charsets.UTF_8)
        position += length
        return value
    }

    fun readUuid(): UUID = UUID(readLong(), readLong())

    fun readPosition(): Long = readLong()

    fun readBytes(length: Int): ByteArray {
        require(length)
        val value = array.copyOfRange(position, position + length)
        position += length
        return value
    }

    fun readRemaining(): ByteArray = readBytes(remaining)

    fun readBytesInto(target: ByteArray, targetOffset: Int, length: Int) {
        require(length)
        System.arraycopy(array, position, target, targetOffset, length)
        position += length
    }

    fun slice(length: Int): PacketReader {
        require(length)
        val slice = PacketReader(array, position, position + length)
        position += length
        return slice
    }

    fun readNbt(): NbtCompound? = NbtCodec.readNetwork(this)

    fun readUtf(): String {
        val length = readUnsignedShort()
        require(length)
        val value = ModifiedUtf8.decode(array, position, length)
        position += length
        return value
    }
}
