package gg.sona.recast.net

object ModifiedUtf8 {

    fun encodedLength(value: String): Int {
        var length = 0
        for (char in value) {
            val code = char.code
            length += when {
                code in 1..0x7F -> 1
                code <= 0x7FF -> 2
                else -> 3
            }
        }
        return length
    }

    fun encode(value: String): ByteArray {
        val result = ByteArray(encodedLength(value))
        var index = 0
        for (char in value) {
            val code = char.code
            when {
                code in 1..0x7F -> result[index++] = code.toByte()
                code <= 0x7FF -> {
                    result[index++] = (0xC0 or ((code shr 6) and 0x1F)).toByte()
                    result[index++] = (0x80 or (code and 0x3F)).toByte()
                }

                else -> {
                    result[index++] = (0xE0 or ((code shr 12) and 0x0F)).toByte()
                    result[index++] = (0x80 or ((code shr 6) and 0x3F)).toByte()
                    result[index++] = (0x80 or (code and 0x3F)).toByte()
                }
            }
        }
        return result
    }

    fun decode(source: ByteArray, offset: Int, length: Int): String {
        val chars = CharArray(length)
        var count = 0
        var index = offset
        val end = offset + length
        while (index < end) {
            val first = source[index].toInt() and 0xFF
            when (first shr 4) {
                0, 1, 2, 3, 4, 5, 6, 7 -> {
                    chars[count++] = first.toChar()
                    index++
                }

                12, 13 -> {
                    if (index + 1 >= end) throw PacketFormatException("malformed modified utf-8")
                    val second = source[index + 1].toInt()
                    chars[count++] = (((first and 0x1F) shl 6) or (second and 0x3F)).toChar()
                    index += 2
                }

                14 -> {
                    if (index + 2 >= end) throw PacketFormatException("malformed modified utf-8")
                    val second = source[index + 1].toInt()
                    val third = source[index + 2].toInt()
                    chars[count++] =
                        (((first and 0x0F) shl 12) or ((second and 0x3F) shl 6) or (third and 0x3F)).toChar()
                    index += 3
                }

                else -> throw PacketFormatException("malformed modified utf-8")
            }
        }
        return String(chars, 0, count)
    }
}
