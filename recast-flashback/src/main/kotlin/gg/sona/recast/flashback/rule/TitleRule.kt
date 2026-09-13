package gg.sona.recast.flashback.rule

import gg.sona.recast.flashback.*

class TitleRule(
    override val id: String,
    private val pattern: Regex,
    private val title: String,
    private val tags: Set<String>
) : TriggerRule {
    override fun evaluate(event: SemanticEvent, context: RuleContext): Trigger? {
        if (event !is SemanticEvent.TitleShown) return null
        val text = listOfNotNull(event.titleJson, event.subtitleJson).joinToString(" ") { ChatText.plain(it) }
        return if (pattern.containsMatchIn(text)) Trigger(id, event.nanos, title, tags) else null
    }
}
