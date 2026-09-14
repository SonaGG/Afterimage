package gg.sona.afterimage.flashback

import gg.sona.afterimage.core.time.Nanos

data class GameProfile(
    val id: String,
    val name: String,
    val rules: List<TriggerRule>,
    val preRollNanos: Long = Nanos.ofSeconds(10),
    val postRollNanos: Long = Nanos.ofSeconds(5),
    val cooldownNanos: Long = Nanos.ofSeconds(2),
)
