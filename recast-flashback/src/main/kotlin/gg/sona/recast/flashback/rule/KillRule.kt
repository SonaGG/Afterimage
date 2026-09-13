package gg.sona.recast.flashback.rule

import gg.sona.recast.flashback.RuleContext
import gg.sona.recast.flashback.SemanticEvent
import gg.sona.recast.flashback.Trigger
import gg.sona.recast.flashback.TriggerRule

class KillRule(
    override val id: String = "kill",
    private val playersOnly: Boolean = true,
    private val tags: Set<String> = setOf("kill")
) : TriggerRule {
    override fun evaluate(event: SemanticEvent, context: RuleContext): Trigger? {
        if (event !is SemanticEvent.PlayerKill || !event.byRecorder) return null
        if (playersOnly && event.victimUuid == null) return null
        return Trigger(id, event.nanos, "Kill${event.victimName?.let { " on $it" } ?: ""}", tags)
    }
}
