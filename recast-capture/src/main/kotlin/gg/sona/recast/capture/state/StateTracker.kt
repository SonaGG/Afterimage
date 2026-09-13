package gg.sona.recast.capture.state

import gg.sona.recast.net.CapturedPacket

interface StateTracker {
    fun reset()
    fun observe(packet: CapturedPacket)
    fun snapshot(nanos: Long): List<CapturedPacket>
}
