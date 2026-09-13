package gg.sona.recast.net

object PackedPosition {

    private const val X_BITS = 26
    private const val Y_BITS = 12
    private const val Z_BITS = 26
    private const val Y_SHIFT = Z_BITS
    private const val X_SHIFT = Y_SHIFT + Y_BITS
    private const val X_MASK = (1L shl X_BITS) - 1
    private const val Y_MASK = (1L shl Y_BITS) - 1
    private const val Z_MASK = (1L shl Z_BITS) - 1

    fun pack(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and X_MASK) shl X_SHIFT) or ((y.toLong() and Y_MASK) shl Y_SHIFT) or (z.toLong() and Z_MASK)

    fun x(packed: Long): Int = (packed shl (64 - X_SHIFT - X_BITS) shr (64 - X_BITS)).toInt()

    fun y(packed: Long): Int = (packed shl (64 - Y_SHIFT - Y_BITS) shr (64 - Y_BITS)).toInt()

    fun z(packed: Long): Int = (packed shl (64 - Z_BITS) shr (64 - Z_BITS)).toInt()
}
