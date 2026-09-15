package gg.sona.afterimage.replay.protocol

import gg.sona.afterimage.capture.state.StateTracker
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.replay.consumer.ReplayConsumer
import gg.sona.afterimage.world.RecorderIdentity
import gg.sona.afterimage.world.WorldTracker

interface ReplayState : StateTracker, ReplayConsumer, WorldTracker {
    val identity: RecorderIdentity

    fun fork(): ReplayState

    fun canDiff(target: ReplayState): Boolean

    fun diff(target: ReplayState, nanos: Long): List<CapturedPacket>

    fun diff(target: ReplayState, nanos: Long, freshEntities: Boolean): List<CapturedPacket> = diff(target, nanos)

    fun adoptFrom(target: ReplayState, nanos: Long)

    fun dropFutureCameraFrames(nanos: Long)
}
