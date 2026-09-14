package gg.sona.afterimage.flashback.rule

import gg.sona.afterimage.flashback.RuleContext
import gg.sona.afterimage.flashback.SemanticEvent
import gg.sona.afterimage.flashback.Trigger
import gg.sona.afterimage.flashback.TriggerRule

class ScoreboardRule(
    override val id: String,
    private val pattern: Regex,
    private val title: String,
    private val tags: Set<String>
) : TriggerRule {
    override fun evaluate(event: SemanticEvent, context: RuleContext): Trigger? {
        if (event !is SemanticEvent.ScoreboardLineChanged) return null
        if (!pattern.containsMatchIn(event.line)) return null
        val previous = event.previousLines.filter { pattern.containsMatchIn(it) }
        if (previous.contains(event.line)) return null
        return Trigger(id, event.nanos, title, tags)
    }
}
