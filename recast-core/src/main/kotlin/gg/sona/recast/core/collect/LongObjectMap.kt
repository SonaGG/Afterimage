package gg.sona.recast.core.collect

class LongObjectMap<V : Any>(expectedSize: Int = 16) {

    private var keys = LongArray(capacityFor(expectedSize))
    private var values = arrayOfNulls<Any?>(keys.size)
    private var mask = keys.size - 1
    private var used = 0
    private var zeroValue: V? = null

    val size: Int get() = used + if (zeroValue != null) 1 else 0

    fun isEmpty(): Boolean = size == 0

    @Suppress("UNCHECKED_CAST")
    operator fun get(key: Long): V? {
        if (key == 0L) return zeroValue
        var index = mix(key) and mask
        while (true) {
            val current = keys[index]
            if (current == key) return values[index] as V
            if (current == 0L) return null
            index = (index + 1) and mask
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun put(key: Long, value: V): V? {
        if (key == 0L) {
            val previous = zeroValue
            zeroValue = value
            return previous
        }
        var index = mix(key) and mask
        while (true) {
            val current = keys[index]
            if (current == key) {
                val previous = values[index] as V
                values[index] = value
                return previous
            }
            if (current == 0L) {
                keys[index] = key
                values[index] = value
                used++
                if (used * 4 >= keys.size * 3) grow()
                return null
            }
            index = (index + 1) and mask
        }
    }

    fun getOrPut(key: Long, factory: () -> V): V {
        val existing = get(key)
        if (existing != null) return existing
        val created = factory()
        put(key, created)
        return created
    }

    @Suppress("UNCHECKED_CAST")
    fun remove(key: Long): V? {
        if (key == 0L) {
            val previous = zeroValue
            zeroValue = null
            return previous
        }
        var index = mix(key) and mask
        while (true) {
            val current = keys[index]
            if (current == 0L) return null
            if (current == key) {
                val previous = values[index] as V
                shiftBack(index)
                used--
                return previous
            }
            index = (index + 1) and mask
        }
    }

    fun clear() {
        keys.fill(0L)
        values.fill(null)
        used = 0
        zeroValue = null
    }

    @Suppress("UNCHECKED_CAST")
    fun forEach(action: (Long, V) -> Unit) {
        forEachEntry { key, value -> action(key, value as V) }
    }

    fun forEachEntry(action: (Long, Any) -> Unit) {
        zeroValue?.let { action(0L, it) }
        for (index in keys.indices) {
            val key = keys[index]
            if (key != 0L) action(key, values[index]!!)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun values(): List<V> {
        val result = ArrayList<V>(size)
        zeroValue?.let { result += it }
        for (index in keys.indices) {
            if (keys[index] != 0L) result += values[index] as V
        }
        return result
    }

    fun keys(): LongArray {
        val result = LongArray(size)
        var count = 0
        if (zeroValue != null) result[count++] = 0L
        for (index in keys.indices) {
            if (keys[index] != 0L) result[count++] = keys[index]
        }
        return result
    }

    private fun shiftBack(removedIndex: Int) {
        var hole = removedIndex
        var index = (hole + 1) and mask
        while (true) {
            val current = keys[index]
            if (current == 0L) break
            val home = mix(current) and mask
            val displaced = if (hole <= index) home !in (hole + 1)..index else home in (index + 1)..hole
            if (displaced) {
                keys[hole] = current
                values[hole] = values[index]
                hole = index
            }
            index = (index + 1) and mask
        }
        keys[hole] = 0L
        values[hole] = null
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size shl 1)
        values = arrayOfNulls(keys.size)
        mask = keys.size - 1
        used = 0
        for (index in oldKeys.indices) {
            val key = oldKeys[index]
            if (key != 0L) insertRaw(key, oldValues[index]!!)
        }
    }

    private fun insertRaw(key: Long, value: Any) {
        var index = mix(key) and mask
        while (keys[index] != 0L) index = (index + 1) and mask
        keys[index] = key
        values[index] = value
        used++
    }

    private companion object {
        fun mix(key: Long): Int {
            val h = key * -0x61c8864680b583ebL
            return (h xor (h ushr 32)).toInt()
        }

        fun capacityFor(expected: Int): Int {
            var capacity = 16
            while (capacity * 3 < expected * 4) capacity = capacity shl 1
            return capacity
        }
    }
}
