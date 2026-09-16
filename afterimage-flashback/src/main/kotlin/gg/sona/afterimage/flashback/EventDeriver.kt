package gg.sona.afterimage.flashback

import gg.sona.afterimage.core.event.Listeners
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.world.WorldEvent
import gg.sona.afterimage.world.WorldEventSink
import gg.sona.afterimage.world.WorldState

class EventDeriver(
    private val world: WorldState,
    private val killWindowNanos: Long = Nanos.ofSeconds(3),
) : WorldEventSink {

    val listeners = Listeners<SemanticEventListener>()

    private var lastAttackTarget = -1
    private var lastAttackNanos = Long.MIN_VALUE
    private var lastOwnDeathNanos = Long.MIN_VALUE
    private var sidebarLines: List<Pair<String, Int>> = emptyList()

    fun reset() {
        lastAttackTarget = -1
        lastAttackNanos = Long.MIN_VALUE
        sidebarLines = emptyList()
    }

    override fun onEvent(event: WorldEvent) {
        val nanos = event.nanos
        when (event) {
            is WorldEvent.Attack -> {
                lastAttackTarget = event.targetId
                lastAttackNanos = nanos
                emit(SemanticEvent.DamageDealt(nanos, event.attackerId, event.targetId, byRecorder = true))
            }

            is WorldEvent.EntityDied -> entityDied(nanos, event.entityId)
            is WorldEvent.LocalDied -> ownDeath(nanos, event.messageJson)
            is WorldEvent.Title -> emit(SemanticEvent.TitleShown(nanos, event.titleJson, event.subtitleJson))
            is WorldEvent.Chat -> if (!event.actionBar) emit(SemanticEvent.ChatReceived(nanos, event.json, ChatText.plain(event.json)))
            is WorldEvent.ObjectiveChanged -> emit(SemanticEvent.ScoreboardObjectiveChanged(nanos, event.name, event.displayName))
            is WorldEvent.ScoreboardChanged -> diffSidebar(nanos)
            is WorldEvent.BossNamed -> emit(SemanticEvent.BossBarShown(nanos, event.entityId, event.nameJson))
            is WorldEvent.Sound -> emit(SemanticEvent.SoundPlayed(nanos, event.name))
            is WorldEvent.Respawned -> emit(SemanticEvent.Respawned(nanos, event.dimension))
            is WorldEvent.Marker -> emit(SemanticEvent.Marker(nanos, event.label))
            else -> Unit
        }
    }

    private fun entityDied(nanos: Long, entityId: Int) {
        val entity = world.entities[entityId]
        val uuid = entity?.uuid
        val name = uuid?.let { world.players.profile(it)?.name }
        emit(SemanticEvent.EntityDeath(nanos, entityId, uuid, name))
        if (entityId == lastAttackTarget && lastAttackNanos != Long.MIN_VALUE && nanos - lastAttackNanos <= killWindowNanos) {
            emit(SemanticEvent.PlayerKill(nanos, world.localPlayer.entityId, entityId, uuid, name, byRecorder = true))
            lastAttackTarget = -1
        }
    }

    private fun ownDeath(nanos: Long, messageJson: String?) {
        if (lastOwnDeathNanos != Long.MIN_VALUE && nanos - lastOwnDeathNanos < Nanos.ofSeconds(1)) return
        lastOwnDeathNanos = nanos
        emit(SemanticEvent.OwnDeath(nanos, messageJson))
    }

    private fun renderLine(entry: String): String {
        val team = world.scoreboard.teamOf(entry)
        return ChatText.strip((team?.prefix ?: "") + entry + (team?.suffix ?: ""))
    }

    private fun diffSidebar(nanos: Long) {
        val objective = world.scoreboard.sidebar()
        val rendered = objective?.scores?.entries?.map { renderLine(it.key) to it.value } ?: emptyList()
        val previous = sidebarLines
        sidebarLines = rendered
        if (objective == null) return
        val previousText = previous.map { it.first }
        for ((line, value) in rendered) {
            if (line in previousText) continue
            emit(SemanticEvent.ScoreboardLineChanged(nanos, objective.name, line, value, previousText))
        }
    }

    private fun emit(event: SemanticEvent) = listeners.dispatch { it.onEvent(event) }
}
