package gg.sona.afterimage.mc189.state

import gg.sona.afterimage.core.time.Nanos

class TitleState(
    val titleJson: String?,
    val subtitleJson: String?,
    val fadeIn: Int,
    val stay: Int,
    val fadeOut: Int,
    val shownAtNanos: Long
) {
    val totalNanos: Long get() = (fadeIn + stay + fadeOut).toLong() * Nanos.PER_TICK

    fun activeAt(nanos: Long): Boolean = titleJson != null && nanos >= shownAtNanos && nanos < shownAtNanos + totalNanos
}