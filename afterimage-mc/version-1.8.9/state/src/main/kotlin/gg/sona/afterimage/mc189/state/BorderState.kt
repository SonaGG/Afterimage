package gg.sona.afterimage.mc189.state

import gg.sona.afterimage.protocol.WorldBorder


class BorderState {
    var centerX: Double = 0.0
    var centerZ: Double = 0.0
    var oldRadius: Double = 60_000_000.0
    var newRadius: Double = 60_000_000.0
    var lerpSpeed: Long = 0L
    var lerpStartedAtNanos: Long = 0L
    var portalTeleportBoundary: Int = 29_999_984
    var warningTime: Int = 15
    var warningBlocks: Int = 5
    var initialized: Boolean = false

    fun apply(packet: WorldBorder, nanos: Long) {
        when (packet.action) {
            WorldBorder.SET_SIZE -> {
                oldRadius = packet.radius
                newRadius = packet.radius
                lerpSpeed = 0L
            }

            WorldBorder.LERP_SIZE -> {
                oldRadius = packet.oldRadius
                newRadius = packet.newRadius
                lerpSpeed = packet.speed
                lerpStartedAtNanos = nanos
            }

            WorldBorder.SET_CENTER -> {
                centerX = packet.centerX
                centerZ = packet.centerZ
            }

            WorldBorder.INITIALIZE -> {
                centerX = packet.centerX
                centerZ = packet.centerZ
                oldRadius = packet.oldRadius
                newRadius = packet.newRadius
                lerpSpeed = packet.speed
                lerpStartedAtNanos = nanos
                portalTeleportBoundary = packet.portalTeleportBoundary
                warningTime = packet.warningTime
                warningBlocks = packet.warningBlocks
            }

            WorldBorder.SET_WARNING_TIME -> warningTime = packet.warningTime
            WorldBorder.SET_WARNING_BLOCKS -> warningBlocks = packet.warningBlocks
        }
        initialized = true
    }

    fun toInitialize(nanos: Long): WorldBorder {
        val elapsedMillis = (nanos - lerpStartedAtNanos) / 1_000_000L
        val remaining = if (lerpSpeed > 0L) maxOf(0L, lerpSpeed - elapsedMillis) else 0L
        val currentRadius = if (lerpSpeed > 0L && remaining > 0L) {
            val progress = (elapsedMillis.toDouble() / lerpSpeed).coerceIn(0.0, 1.0)
            oldRadius + (newRadius - oldRadius) * progress
        } else {
            newRadius
        }
        return WorldBorder(
            WorldBorder.INITIALIZE,
            0.0,
            currentRadius,
            newRadius,
            remaining,
            centerX,
            centerZ,
            portalTeleportBoundary,
            warningTime,
            warningBlocks
        )
    }
}

