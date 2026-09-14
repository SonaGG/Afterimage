package gg.sona.afterimage.index

internal class DoubleColumn(capacity: Int = 64) {
    private var data = DoubleArray(capacity)
    var size = 0
        private set

    fun add(value: Double) {
        if (size == data.size) data = data.copyOf(maxOf(16, size * 2))
        data[size++] = value
    }

    fun toArray(): DoubleArray = data.copyOf(size)
}

internal class FloatColumn(capacity: Int = 64) {
    private var data = FloatArray(capacity)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(maxOf(16, size * 2))
        data[size++] = value
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

internal class IntColumn(capacity: Int = 64) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(maxOf(16, size * 2))
        data[size++] = value
    }

    operator fun get(index: Int): Int = data[index]

    fun toArray(): IntArray = data.copyOf(size)
}

internal class LongColumn(capacity: Int = 64) {
    private var data = LongArray(capacity)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == data.size) data = data.copyOf(maxOf(16, size * 2))
        data[size++] = value
    }

    operator fun get(index: Int): Long = data[index]

    fun toArray(): LongArray = data.copyOf(size)
}

internal class ShortColumn(capacity: Int = 64) {
    private var data = ShortArray(capacity)
    var size = 0
        private set

    fun add(value: Short) {
        if (size == data.size) data = data.copyOf(maxOf(16, size * 2))
        data[size++] = value
    }

    fun toArray(): ShortArray = data.copyOf(size)
}

internal class ByteColumn(capacity: Int = 64) {
    private var data = ByteArray(capacity)
    var size = 0
        private set

    fun add(value: Byte) {
        if (size == data.size) data = data.copyOf(maxOf(16, size * 2))
        data[size++] = value
    }

    fun toArray(): ByteArray = data.copyOf(size)
}
