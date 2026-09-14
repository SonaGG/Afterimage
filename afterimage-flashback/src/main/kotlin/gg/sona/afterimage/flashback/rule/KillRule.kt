package gg.sona.afterimage.flashback.rule

import gg.sona.afterimage.flashback.RuleContext
import gg.sona.afterimage.flashback.SemanticEvent
import gg.sona.afterimage.flashback.Trigger
import gg.sona.afterimage.flashback.TriggerRule

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
