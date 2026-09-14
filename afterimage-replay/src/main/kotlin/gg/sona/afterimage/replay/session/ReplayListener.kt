package gg.sona.afterimage.replay.session

import gg.sona.afterimage.replay.consumer.DeliveryMode

interface ReplayListener {
    fun onPositionChanged(positionNanos: Long, mode: DeliveryMode) {}

    fun onPlayingChanged(playing: Boolean) {}

    fun onSpeedChanged(speed: Double) {}

    fun onReachedEnd() {}
}