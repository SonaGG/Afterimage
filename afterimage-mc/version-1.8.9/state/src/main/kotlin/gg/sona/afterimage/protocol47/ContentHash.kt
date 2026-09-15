package gg.sona.afterimage.protocol47

object ContentHash {
    private const val PRIME = 0x100000001b3L
    private const val OFFSET = -0x340d631b7bdddcdbL

    fun seed(): Long = OFFSET

    fun mix(hash: Long, value: Long): Long {
        var result = hash xor value
        result *= PRIME
        return result xor (result ushr 29)
    }

    fun mix(hash: Long, bytes: ByteArray): Long {
        var result = hash
        var index = 0
        val fullWords = bytes.size and 7.inv()
        while (index < fullWords) {
            val word = (bytes[index].toLong() and 0xFF) or
                    ((bytes[index + 1].toLong() and 0xFF) shl 8) or
                    ((bytes[index + 2].toLong() and 0xFF) shl 16) or
                    ((bytes[index + 3].toLong() and 0xFF) shl 24) or
                    ((bytes[index + 4].toLong() and 0xFF) shl 32) or
                    ((bytes[index + 5].toLong() and 0xFF) shl 40) or
                    ((bytes[index + 6].toLong() and 0xFF) shl 48) or
                    ((bytes[index + 7].toLong() and 0xFF) shl 56)
            result = mix(result, word)
            index += 8
        }
        while (index < bytes.size) {
            result = mix(result, bytes[index].toLong() and 0xFF)
            index++
        }
        return mix(result, bytes.size.toLong())
    }
}
