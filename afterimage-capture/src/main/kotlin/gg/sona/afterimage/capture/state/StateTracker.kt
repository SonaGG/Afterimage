package gg.sona.afterimage.capture.state

import gg.sona.afterimage.net.CapturedPacket

interface StateTracker {
    fun reset()
    fun observe(packet: CapturedPacket)
    fun snapshot(nanos: Long): List<CapturedPacket>
}
