package gg.sona.recast.replay.session

import gg.sona.recast.replay.consumer.DeliveryMode

interface ReplayListener {
    fun onPositionChanged(positionNanos: Long, mode: DeliveryMode) {}

    fun onPlayingChanged(playing: Boolean) {}

    fun onSpeedChanged(speed: Double) {}

    fun onReachedEnd() {}
}