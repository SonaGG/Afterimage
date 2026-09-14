package gg.sona.afterimage.render


interface TimeMapping {
    val outputDurationNanos: Long

    fun replayNanosAt(outputNanos: Long): Long
}
