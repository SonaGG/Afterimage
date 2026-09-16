package gg.sona.afterimage.world

import gg.sona.afterimage.world.camera.CameraSamples
import gg.sona.afterimage.world.interpolation.Interpolation
import java.util.*

data class ItemRef(val id: Int, val count: Int, val label: String)

interface EntityState {
    val id: Int
    val kind: EntityKind
    val type: Int
    val uuid: UUID?
    val spawnedAtNanos: Long
    val x: Double
    val y: Double
    val z: Double
    val yawDegrees: Float
    val pitchDegrees: Float
    val headYawDegrees: Float
    val onGround: Boolean
    val dead: Boolean
    val vehicleId: Int
    val health: Float
    val flags: Int
    val isPlayer: Boolean get() = kind == EntityKind.PLAYER
    val isProjectile: Boolean
    val history: MotionHistory
    fun equipment(slot: Int): ItemRef?
    fun pose(): Pose
    fun poseAt(nanos: Long, interpolation: Interpolation): Pose
    fun visibleAt(nanos: Long): Boolean
}

interface EntityStates {
    val size: Int
    operator fun get(id: Int): EntityState?
    fun values(): List<EntityState>
}

interface LocalPlayerState {
    val entityId: Int
    val uuid: UUID?
    val name: String?
    val hasPosition: Boolean
    val x: Double
    val y: Double
    val z: Double
    val yaw: Float
    val pitch: Float
    val onGround: Boolean
    val health: Float
    val food: Int
    val gameMode: Int
    val isSpectator: Boolean
    val sneaking: Boolean
    val sprinting: Boolean
    val flags: Int
    val vehicleId: Int
    val heldSlot: Int
    val held: ItemRef?
    val history: MotionHistory
    val cameraFrames: CameraSamples
    fun equipment(slot: Int): ItemRef?
    fun poseAt(nanos: Long, interpolation: Interpolation): Pose
}

interface PlayerProfile {
    val uuid: UUID
    val name: String
    val gameMode: Int
    val ping: Int
    val displayNameJson: String?
}

interface PlayerListState {
    val listed: Collection<PlayerProfile>
    fun profile(uuid: UUID): PlayerProfile?
    fun knownProfile(uuid: UUID): PlayerProfile?
}

interface ObjectiveState {
    val name: String
    val displayName: String
    val scores: Map<String, Int>
}

interface TeamState {
    val name: String
    val prefix: String
    val suffix: String
    val members: Set<String>
}

interface ScoreboardState {
    val allObjectives: Collection<ObjectiveState>
    val allTeams: Collection<TeamState>
    fun sidebar(): ObjectiveState?
    fun tabList(): ObjectiveState?
    fun teamOf(entry: String): TeamState?
}

interface WorldState {
    val joined: Boolean
    val dimension: Int
    val timeOfDay: Long
    val loadedChunks: Int
    val trackedPackets: Long
    val recorderIdentity: RecorderIdentity
    val localPlayer: LocalPlayerState
    val entities: EntityStates
    val players: PlayerListState
    val scoreboard: ScoreboardState
    fun blockState(x: Int, y: Int, z: Int): Int
    fun dimensionName(dimension: Int): String
}
