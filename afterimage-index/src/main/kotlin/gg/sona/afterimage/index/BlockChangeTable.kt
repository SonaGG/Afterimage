package gg.sona.afterimage.index

class BlockChangeTable(
    val nanos: LongArray,
    val tick: IntArray,
    val x: IntArray,
    val y: IntArray,
    val z: IntArray,
    val from: IntArray,
    val to: IntArray,
    val by: IntArray,
) {
    val size: Int get() = nanos.size

    fun lowerBound(target: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (nanos[mid] < target) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        val EMPTY = BlockChangeTable(
            LongArray(0),
            IntArray(0),
            IntArray(0),
            IntArray(0),
            IntArray(0),
            IntArray(0),
            IntArray(0),
            IntArray(0)
        )
    }
}
