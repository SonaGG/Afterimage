package gg.sona.recast.flashback.rule

import gg.sona.recast.flashback.RuleContext
import gg.sona.recast.flashback.SemanticEvent
import gg.sona.recast.flashback.Trigger
import gg.sona.recast.flashback.TriggerRule

class OwnDeathRule(override val id: String = "death", private val tags: Set<String> = setOf("death")) : TriggerRule {
    override fun evaluate(event: SemanticEvent, context: RuleContext): Trigger? =
        if (event is SemanticEvent.OwnDeath) Trigger(id, event.nanos, "Death", tags) else null
}
