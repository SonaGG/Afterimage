package gg.sona.afterimage.protocol


class MetadataEntry(val index: Int, val type: Int, val value: Any) {

    fun byteValue(): Int = (value as Byte).toInt()

    override fun toString(): String = "Meta($index:$type=$value)"

    companion object {
        const val BYTE = 0
        const val SHORT = 1
        const val INT = 2
        const val FLOAT = 3
        const val STRING = 4
        const val SLOT = 5
        const val BLOCK_POS = 6
        const val ROTATIONS = 7
        const val TERMINATOR = 0x7F

        fun ofByte(index: Int, value: Int): MetadataEntry = MetadataEntry(index, BYTE, value.toByte())

        fun ofFloat(index: Int, value: Float): MetadataEntry = MetadataEntry(index, FLOAT, value)

        fun ofString(index: Int, value: String): MetadataEntry = MetadataEntry(index, STRING, value)
    }
}
