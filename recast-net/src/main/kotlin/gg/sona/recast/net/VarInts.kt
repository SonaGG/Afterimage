package gg.sona.recast.net

object VarInts {

    const val MAX_VARINT_BYTES = 5
    const val MAX_VARLONG_BYTES = 10

    fun sizeOf(value: Int): Int {
        var remaining = value
        var size = 1
        while (remaining and -0x80 != 0) {
            remaining = remaining ushr 7
            size++
        }
        return size
    }

    fun sizeOfLong(value: Long): Int {
        var remaining = value
        var size = 1
        while (remaining and -0x80L != 0L) {
            remaining = remaining ushr 7
            size++
        }
        return size
    }

    fun write(target: ByteArray, offset: Int, value: Int): Int {
        var remaining = value
        var position = offset
        while (remaining and -0x80 != 0) {
            target[position++] = ((remaining and 0x7F) or 0x80).toByte()
            remaining = remaining ushr 7
        }
        target[position++] = remaining.toByte()
        return position - offset
    }

    fun read(source: ByteArray, offset: Int, limit: Int): Long {
        var result = 0
        var shift = 0
        var position = offset
        while (position < limit) {
            val byte = source[position++].toInt()
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return pack(result, position - offset)
            shift += 7
            if (shift > 35) throw PacketFormatException("varint too long")
        }
        throw PacketFormatException("varint truncated")
    }

    fun value(packed: Long): Int = (packed shr 8).toInt()

    fun length(packed: Long): Int = (packed and 0xFF).toInt()

    private fun pack(value: Int, length: Int): Long = (value.toLong() shl 8) or length.toLong()
}
