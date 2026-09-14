package gg.sona.afterimage.flashback.rule

import gg.sona.afterimage.flashback.RuleContext
import gg.sona.afterimage.flashback.SemanticEvent
import gg.sona.afterimage.flashback.Trigger
import gg.sona.afterimage.flashback.TriggerRule

class MarkerRule(override val id: String = "marker") : TriggerRule {
    override fun evaluate(event: SemanticEvent, context: RuleContext): Trigger? =
        if (event is SemanticEvent.Marker) Trigger(
            id,
            event.nanos,
            event.label.ifEmpty { "Marker" },
            setOf("marker")
        ) else null
}
