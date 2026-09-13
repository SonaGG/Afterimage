package gg.sona.recast.replay.consumer

import gg.sona.recast.net.CapturedPacket

interface ReplayConsumer {
    fun onReset(reason: ResetReason) {}
    fun onPacket(packet: CapturedPacket, mode: DeliveryMode)
    fun onSettled(positionNanos: Long, mode: DeliveryMode) {}
}
