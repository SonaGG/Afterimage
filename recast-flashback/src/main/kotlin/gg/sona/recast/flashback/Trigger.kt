package gg.sona.recast.flashback


data class Trigger(
    val ruleId: String,
    val nanos: Long,
    val title: String,
    val tags: Set<String>,
    val preRollNanos: Long? = null,
    val postRollNanos: Long? = null,
)
