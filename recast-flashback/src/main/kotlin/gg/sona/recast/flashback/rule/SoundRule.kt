package gg.sona.recast.flashback.rule

import gg.sona.recast.flashback.RuleContext
import gg.sona.recast.flashback.SemanticEvent
import gg.sona.recast.flashback.Trigger
import gg.sona.recast.flashback.TriggerRule

class SoundRule(
    override val id: String,
    private val pattern: Regex,
    private val title: String,
    private val tags: Set<String>
) : TriggerRule {
    override fun evaluate(event: SemanticEvent, context: RuleContext): Trigger? {
        if (event !is SemanticEvent.SoundPlayed) return null
        return if (pattern.containsMatchIn(event.name)) Trigger(id, event.nanos, title, tags) else null
    }
}
