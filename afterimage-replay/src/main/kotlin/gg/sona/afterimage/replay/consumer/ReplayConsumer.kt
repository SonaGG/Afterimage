package gg.sona.afterimage.replay.consumer

import gg.sona.afterimage.net.CapturedPacket

interface ReplayConsumer {
    fun onReset(reason: ResetReason) {}
    fun onPacket(packet: CapturedPacket, mode: DeliveryMode)
    fun onSettled(positionNanos: Long, mode: DeliveryMode) {}
}
