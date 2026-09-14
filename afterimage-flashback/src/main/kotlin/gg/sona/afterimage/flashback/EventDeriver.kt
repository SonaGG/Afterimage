package gg.sona.afterimage.flashback

import gg.sona.afterimage.capture.packet.PacketObserver
import gg.sona.afterimage.core.event.Listeners
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.protocol.*
import gg.sona.afterimage.replay.consumer.DeliveryMode
import gg.sona.afterimage.replay.consumer.ReplayConsumer
import gg.sona.afterimage.replay.consumer.ResetReason
import gg.sona.afterimage.replay.state.shadow.ShadowClient

class EventDeriver(
    private val shadow: ShadowClient,
    private val killWindowNanos: Long = Nanos.ofSeconds(3),
) : PacketObserver, ReplayConsumer {

    val listeners = Listeners<SemanticEventListener>()

    private var lastAttackTarget = -1
    private var lastAttackNanos = Long.MIN_VALUE
    private var lastOwnDeathNanos = Long.MIN_VALUE
    private val bossEntities = HashSet<Int>()
    private var sidebarLines: List<Pair<String, Int>> = emptyList()

    override fun onPacket(packet: CapturedPacket) = derive(packet)

    override fun onPacket(packet: CapturedPacket, mode: DeliveryMode) {
        if (mode == DeliveryMode.LIVE) derive(packet)
    }

    override fun onReset(reason: ResetReason) {
        lastAttackTarget = -1
        lastAttackNanos = Long.MIN_VALUE
        bossEntities.clear()
        sidebarLines = emptyList()
    }

    fun derive(packet: CapturedPacket) {
        derive(PacketCodec.decode(packet) ?: return, packet.timestampNanos)
    }

    fun derive(decoded: PlayPacket, nanos: Long) {
        when (decoded) {
            is UseEntity -> if (decoded.type == UseEntity.ATTACK) {
                lastAttackTarget = decoded.targetId
                lastAttackNanos = nanos
                emit(SemanticEvent.DamageDealt(nanos, shadow.localPlayer.entityId, decoded.targetId, byRecorder = true))
            }

            is EntityStatus -> if (decoded.status == EntityStatus.DEAD) entityDied(nanos, decoded.entityId)
            is CombatEvent -> if (decoded.event == CombatEvent.ENTITY_DEAD && decoded.playerId == shadow.localPlayer.entityId) ownDeath(
                nanos,
                decoded.messageJson
            )

            is UpdateHealth -> if (decoded.health <= 0f) ownDeath(nanos, null)
            is Title -> when (decoded.action) {
                Title.SET_TITLE -> emit(SemanticEvent.TitleShown(nanos, decoded.textJson, null))
                Title.SET_SUBTITLE -> emit(SemanticEvent.TitleShown(nanos, null, decoded.textJson))
            }

            is ChatMessage -> if (decoded.position != 2) emit(
                SemanticEvent.ChatReceived(
                    nanos,
                    decoded.json,
                    ChatText.plain(decoded.json)
                )
            )

            is UpdateScore -> diffSidebar(nanos)
            is Teams -> diffSidebar(nanos)
            is DisplayScoreboard -> diffSidebar(nanos)
            is ScoreboardObjective -> {
                if (decoded.mode != ScoreboardObjective.REMOVE) emit(
                    SemanticEvent.ScoreboardObjectiveChanged(
                        nanos,
                        decoded.name,
                        decoded.displayName
                    )
                )
                diffSidebar(nanos)
            }

            is SpawnMob -> if (decoded.type == WITHER || decoded.type == ENDER_DRAGON) {
                bossEntities += decoded.entityId
                customName(decoded.metadata)?.let { emit(SemanticEvent.BossBarShown(nanos, decoded.entityId, it)) }
            }

            is EntityMetadata -> if (decoded.entityId in bossEntities) customName(decoded.metadata)?.let {
                emit(
                    SemanticEvent.BossBarShown(nanos, decoded.entityId, it)
                )
            }

            is SoundEffect -> emit(SemanticEvent.SoundPlayed(nanos, decoded.name))
            is Respawn -> emit(SemanticEvent.Respawned(nanos, decoded.dimension))
            is SessionMark -> if (decoded.kind == SessionMark.USER_MARKER) emit(
                SemanticEvent.Marker(
                    nanos,
                    decoded.label
                )
            )

            else -> Unit
        }
    }

    private fun entityDied(nanos: Long, entityId: Int) {
        val entity = shadow.entities[entityId]
        val uuid = entity?.uuid
        val name = uuid?.let { shadow.players.profile(it)?.name }
        emit(SemanticEvent.EntityDeath(nanos, entityId, uuid, name))
        if (entityId == lastAttackTarget && lastAttackNanos != Long.MIN_VALUE && nanos - lastAttackNanos <= killWindowNanos) {
            emit(SemanticEvent.PlayerKill(nanos, shadow.localPlayer.entityId, entityId, uuid, name, byRecorder = true))
            lastAttackTarget = -1
        }
    }

    private fun ownDeath(nanos: Long, messageJson: String?) {
        if (lastOwnDeathNanos != Long.MIN_VALUE && nanos - lastOwnDeathNanos < Nanos.ofSeconds(1)) return
        lastOwnDeathNanos = nanos
        emit(SemanticEvent.OwnDeath(nanos, messageJson))
    }

    private fun renderLine(entry: String): String {
        val team = shadow.scoreboard.teamOf(entry)
        return ChatText.strip((team?.prefix ?: "") + entry + (team?.suffix ?: ""))
    }

    private fun diffSidebar(nanos: Long) {
        val objective = shadow.scoreboard.sidebar()
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

    private fun customName(metadata: List<MetadataEntry>): String? =
        metadata.firstOrNull { it.index == CUSTOM_NAME_INDEX && it.type == MetadataEntry.STRING }?.value as? String

    private fun emit(event: SemanticEvent) = listeners.dispatch { it.onEvent(event) }

    private companion object {
        const val WITHER = 64
        const val ENDER_DRAGON = 63
        const val CUSTOM_NAME_INDEX = 2
    }
}
