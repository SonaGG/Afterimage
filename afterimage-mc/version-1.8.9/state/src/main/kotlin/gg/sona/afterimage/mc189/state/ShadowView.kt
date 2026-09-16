package gg.sona.afterimage.mc189.state

import gg.sona.afterimage.world.EntityState
import gg.sona.afterimage.world.EntityStates
import gg.sona.afterimage.world.LocalPlayerState
import gg.sona.afterimage.world.PlayerListState
import gg.sona.afterimage.world.RecorderIdentity
import gg.sona.afterimage.world.ScoreboardState
import gg.sona.afterimage.world.WorldState

class ShadowView(private val client: ShadowClient) : WorldState, EntityStates {
    override val joined: Boolean get() = client.joined
    override val dimension: Int get() = client.level.dimension
    override val timeOfDay: Long get() = client.level.timeOfDay
    override val loadedChunks: Int get() = client.level.chunks.size
    override val trackedPackets: Long get() = client.packetsApplied
    override val recorderIdentity: RecorderIdentity get() = client.recorderIdentity
    override val localPlayer: LocalPlayerState get() = client.localPlayer
    override val entities: EntityStates get() = this
    override val players: PlayerListState get() = client.players
    override val scoreboard: ScoreboardState get() = client.scoreboard

    override fun blockState(x: Int, y: Int, z: Int): Int = client.level.blockState(x, y, z)

    override fun dimensionName(dimension: Int): String = when (dimension) {
        -1 -> "Nether"
        0 -> "Overworld"
        1 -> "End"
        else -> "Dimension $dimension"
    }

    override val size: Int get() = client.entities.size

    override fun get(id: Int): EntityState? = client.entities[id]

    override fun values(): List<EntityState> = client.entities.values()
}
