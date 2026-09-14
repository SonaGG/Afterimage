package gg.sona.afterimage.capture.packet

import gg.sona.afterimage.net.CapturedPacket

fun interface PacketObserver {
    fun onPacket(packet: CapturedPacket)
}
