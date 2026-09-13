package gg.sona.recast.capture.packet

import gg.sona.recast.net.CapturedPacket

fun interface PacketObserver {
    fun onPacket(packet: CapturedPacket)
}
