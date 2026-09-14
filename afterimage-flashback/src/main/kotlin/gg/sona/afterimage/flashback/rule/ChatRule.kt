package gg.sona.afterimage.flashback.rule

import gg.sona.afterimage.flashback.RuleContext
import gg.sona.afterimage.flashback.SemanticEvent
import gg.sona.afterimage.flashback.Trigger
import gg.sona.afterimage.flashback.TriggerRule

class ChatRule(
    override val id: String,
    private val pattern: Regex,
    private val title: String,
    private val tags: Set<String>
) : TriggerRule {
    override fun evaluate(event: SemanticEvent, context: RuleContext): Trigger? {
        if (event !is SemanticEvent.ChatReceived) return null
        return if (pattern.containsMatchIn(event.plainText)) Trigger(id, event.nanos, title, tags) else null
    }
}
