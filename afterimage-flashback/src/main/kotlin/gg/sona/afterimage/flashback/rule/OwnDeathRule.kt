package gg.sona.afterimage.flashback.rule

import gg.sona.afterimage.flashback.RuleContext
import gg.sona.afterimage.flashback.SemanticEvent
import gg.sona.afterimage.flashback.Trigger
import gg.sona.afterimage.flashback.TriggerRule

class OwnDeathRule(override val id: String = "death", private val tags: Set<String> = setOf("death")) : TriggerRule {
    override fun evaluate(event: SemanticEvent, context: RuleContext): Trigger? =
        if (event is SemanticEvent.OwnDeath) Trigger(id, event.nanos, "Death", tags) else null
}
