package gg.sona.afterimage.replay.protocol

import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.world.RecorderIdentity
import gg.sona.afterimage.world.camera.CameraSample

interface ReplayProtocol {
    val version: Int

    val minecraftVersion: String

    fun createState(identity: RecorderIdentity): ReplayState

    fun isJoin(packet: CapturedPacket): Boolean

    fun isTickMarker(packet: CapturedPacket): Boolean

    fun respawnDimension(packet: CapturedPacket): Int?

    fun cameraSample(packet: CapturedPacket): CameraSample?

    fun isTransient(packet: CapturedPacket): Boolean

    fun isVisualEvent(packet: CapturedPacket): Boolean

    fun marker(kind: Int, label: String, nanos: Long): CapturedPacket
}
