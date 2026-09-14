package gg.sona.afterimage.mc

import org.apache.logging.log4j.LogManager
import java.util.*

object DeterministicRandom {
    private val logger = LogManager.getLogger("Afterimage")
    private var mathRandom: Random? = null
    private var resolved = false

    fun mix(seed: Long, salt: Long): Long {
        var value = seed * -7046029254386353131L + salt * 0x9E3779B97F4A7C15uL.toLong()
        value = value xor (value ushr 31)
        value *= -4265267296055464877L
        value = value xor (value ushr 29)
        return value
    }

    fun reseedMath(seed: Long) {
        val random = shared() ?: return
        random.setSeed(seed)
    }

    private fun shared(): Random? {
        if (resolved) return mathRandom
        resolved = true
        mathRandom = resolve()
        if (mathRandom == null) logger.warn("Afterimage could not reach Math.random")
        return mathRandom
    }

    // tung tung tung sahur
    private fun resolve(): Random? {
        val holder =
            runCatching { Class.forName("java.lang.Math\$RandomNumberGeneratorHolder") }.getOrNull() ?: return null
        val field = runCatching { holder.getDeclaredField("randomNumberGenerator") }.getOrNull() ?: return null
        runCatching {
            field.isAccessible = true
            return field.get(null) as? Random
        }
        return runCatching {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val base =
                unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)
            val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
                .invoke(unsafe, field) as Long
            unsafeClass.getMethod("getObject", Any::class.java, java.lang.Long.TYPE)
                .invoke(unsafe, base, offset) as? Random
        }.getOrNull()
    }
}
