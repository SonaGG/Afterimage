package gg.sona.afterimage.render


class LinearTimeMapping(private val startNanos: Long, private val endNanos: Long) : TimeMapping {
    override val outputDurationNanos: Long get() = maxOf(0L, endNanos - startNanos)

    override fun replayNanosAt(outputNanos: Long): Long = startNanos + outputNanos
}
