package gg.sona.recast.flashback

import gg.sona.recast.core.event.Listeners
import gg.sona.recast.core.log.RecastLog
import gg.sona.recast.core.time.Nanos
import java.util.*

class FlashbackEngine(
    profiles: List<GameProfile> = listOf(Profiles.genericPvp()),
    private val recentWindowNanos: Long = Nanos.ofSeconds(10),
    private val maxRecent: Int = 256,
) : SemanticEventListener {

    private val logger = RecastLog.logger("recast.flashback")
    private val recent = ArrayDeque<SemanticEvent>()
    private val lastFired = HashMap<String, Long>()
    val listeners = Listeners<ClipRequestListener>()

    @Volatile
    var profiles: List<GameProfile> = profiles

    @Volatile
    var enabled: Boolean = true

    var requestsIssued: Long = 0L
        private set

    override fun onEvent(event: SemanticEvent) {
        if (!enabled) return
        val context = RuleContext(recent.toList())
        for (profile in profiles) {
            for (rule in profile.rules) {
                val trigger = try {
                    rule.evaluate(event, context) ?: continue
                } catch (error: Throwable) {
                    logger.warn("Rule ${rule.id} failed", error)
                    continue
                }
                val key = "${profile.id}/${rule.id}"
                val previous = lastFired[key]
                if (previous != null && trigger.nanos - previous < profile.cooldownNanos) continue
                lastFired[key] = trigger.nanos
                requestsIssued++
                val request = ClipRequest(trigger, profile)
                listeners.dispatch { it.onClipRequested(request) }
            }
        }
        remember(event)
    }

    fun reset() {
        recent.clear()
        lastFired.clear()
    }

    private fun remember(event: SemanticEvent) {
        recent.addLast(event)
        while (recent.size > maxRecent || (recent.isNotEmpty() && event.nanos - recent.first().nanos > recentWindowNanos)) recent.removeFirst()
    }
}
