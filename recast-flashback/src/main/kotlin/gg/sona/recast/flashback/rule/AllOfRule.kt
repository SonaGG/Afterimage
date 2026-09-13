package gg.sona.recast.flashback.rule

import gg.sona.recast.flashback.RuleContext
import gg.sona.recast.flashback.SemanticEvent
import gg.sona.recast.flashback.Trigger
import gg.sona.recast.flashback.TriggerRule

class AllOfRule(
    override val id: String,
    private val windowNanos: Long,
    private val title: String,
    private val tags: Set<String>,
    private val conditions: List<(SemanticEvent) -> Boolean>
) : TriggerRule {
    override fun evaluate(event: SemanticEvent, context: RuleContext): Trigger? {
        val window = context.recent.filter { event.nanos - it.nanos in 0..windowNanos } + event
        val satisfied = conditions.all { condition -> window.any(condition) }
        return if (satisfied && conditions.any { it(event) }) Trigger(id, event.nanos, title, tags) else null
    }
}
