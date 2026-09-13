package gg.sona.recast.flashback


interface TriggerRule {
    val id: String

    fun evaluate(event: SemanticEvent, context: RuleContext): Trigger?
}
