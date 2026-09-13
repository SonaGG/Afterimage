package gg.sona.recast.render


interface TimeMapping {
    val outputDurationNanos: Long

    fun replayNanosAt(outputNanos: Long): Long
}
