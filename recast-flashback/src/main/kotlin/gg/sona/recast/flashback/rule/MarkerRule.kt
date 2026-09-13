package gg.sona.recast.flashback.rule

import gg.sona.recast.flashback.RuleContext
import gg.sona.recast.flashback.SemanticEvent
import gg.sona.recast.flashback.Trigger
import gg.sona.recast.flashback.TriggerRule

class MarkerRule(override val id: String = "marker") : TriggerRule {
    override fun evaluate(event: SemanticEvent, context: RuleContext): Trigger? =
        if (event is SemanticEvent.Marker) Trigger(
            id,
            event.nanos,
            event.label.ifEmpty { "Marker" },
            setOf("marker")
        ) else null
}
