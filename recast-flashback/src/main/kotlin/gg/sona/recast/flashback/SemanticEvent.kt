package gg.sona.recast.flashback

import java.util.*

sealed class SemanticEvent {
    abstract val nanos: Long

    data class EntityDeath(
        override val nanos: Long,
        val entityId: Int,
        val victimUuid: UUID?,
        val victimName: String?
    ) : SemanticEvent()

    data class PlayerKill(
        override val nanos: Long,
        val killerId: Int,
        val victimId: Int,
        val victimUuid: UUID?,
        val victimName: String?,
        val byRecorder: Boolean
    ) : SemanticEvent()

    data class DamageDealt(override val nanos: Long, val attackerId: Int, val targetId: Int, val byRecorder: Boolean) :
        SemanticEvent()

    data class OwnDeath(override val nanos: Long, val messageJson: String?) : SemanticEvent()

    data class TitleShown(override val nanos: Long, val titleJson: String?, val subtitleJson: String?) : SemanticEvent()

    data class ChatReceived(override val nanos: Long, val json: String, val plainText: String) : SemanticEvent()

    data class ScoreboardLineChanged(
        override val nanos: Long,
        val objective: String,
        val line: String,
        val value: Int,
        val previousLines: List<String>
    ) : SemanticEvent()

    data class ScoreboardObjectiveChanged(override val nanos: Long, val objective: String, val displayName: String?) :
        SemanticEvent()

    data class BossBarShown(override val nanos: Long, val entityId: Int, val nameJson: String) : SemanticEvent()

    data class SoundPlayed(override val nanos: Long, val name: String) : SemanticEvent()

    data class AchievementGet(override val nanos: Long, val json: String) : SemanticEvent()

    data class Respawned(override val nanos: Long, val dimension: Int) : SemanticEvent()

    data class Marker(override val nanos: Long, val label: String) : SemanticEvent()
}
