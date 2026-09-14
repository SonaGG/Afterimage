package gg.sona.afterimage.index

class EventTable(
    val nanos: LongArray,
    val tick: IntArray,
    val kind: ByteArray,
    val a: IntArray,
    val b: IntArray,
    val x: DoubleArray,
    val y: DoubleArray,
    val z: DoubleArray,
    val value: FloatArray,
    val text: Array<String?>,
) {
    val size: Int get() = nanos.size

    fun kindAt(index: Int): IndexEventKind = IndexEventKind.of(kind[index].toInt())

    fun hasPosition(index: Int): Boolean = !x[index].isNaN()

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
        val EMPTY = EventTable(
            LongArray(0), IntArray(0), ByteArray(0), IntArray(0), IntArray(0),
            DoubleArray(0), DoubleArray(0), DoubleArray(0), FloatArray(0), arrayOfNulls(0)
        )
    }
}
