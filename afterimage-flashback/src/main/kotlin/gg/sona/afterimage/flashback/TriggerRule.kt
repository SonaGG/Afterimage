package gg.sona.afterimage.flashback


interface TriggerRule {
    val id: String

    fun evaluate(event: SemanticEvent, context: RuleContext): Trigger?
}
