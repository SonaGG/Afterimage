package gg.sona.afterimage.world

import gg.sona.afterimage.net.CapturedPacket
import java.util.*

sealed class WorldEvent(val nanos: Long) {
    class WorldJoined(nanos: Long) : WorldEvent(nanos)
    class DimensionChanged(nanos: Long, val dimension: Int) : WorldEvent(nanos)
    class Respawned(nanos: Long, val dimension: Int) : WorldEvent(nanos)
    class BlockChanged(nanos: Long, val x: Int, val y: Int, val z: Int, val state: Int, val byEntity: Int) : WorldEvent(nanos)
    class EntitySpawned(nanos: Long, val entityId: Int, val shooterHint: Int) : WorldEvent(nanos)
    class EntityRemoved(nanos: Long, val entityId: Int) : WorldEvent(nanos)
    class ArmSwing(nanos: Long, val entityId: Int) : WorldEvent(nanos)
    class Critical(nanos: Long, val entityId: Int, val magic: Boolean) : WorldEvent(nanos)
    class Eating(nanos: Long, val entityId: Int) : WorldEvent(nanos)
    class Hurt(nanos: Long, val entityId: Int) : WorldEvent(nanos)
    class LocalHealth(nanos: Long, val health: Float) : WorldEvent(nanos)
    class Attack(nanos: Long, val attackerId: Int, val targetId: Int) : WorldEvent(nanos)
    class EntityDied(nanos: Long, val entityId: Int) : WorldEvent(nanos)
    class LocalDied(nanos: Long, val messageJson: String?) : WorldEvent(nanos)
    class UseItem(nanos: Long, val entityId: Int, val using: Boolean, val item: ItemRef?) : WorldEvent(nanos)
    class Equipped(nanos: Long, val entityId: Int, val slot: Int, val item: ItemRef?) : WorldEvent(nanos)
    class ItemCollected(nanos: Long, val collectorId: Int, val collectedId: Int, val stack: ItemRef?) : WorldEvent(nanos)
    class EffectApplied(nanos: Long, val entityId: Int, val effectId: Int, val label: String) : WorldEvent(nanos)
    class Explosion(nanos: Long, val x: Double, val y: Double, val z: Double, val radius: Float, val blocks: Int) : WorldEvent(nanos)
    class Sound(nanos: Long, val name: String, val x: Double, val y: Double, val z: Double, val volume: Float) : WorldEvent(nanos)
    class PlayerJoined(nanos: Long, val uuid: UUID, val name: String) : WorldEvent(nanos)
    class PlayerLeft(nanos: Long, val uuid: UUID, val name: String?) : WorldEvent(nanos)
    class Digging(nanos: Long, val entityId: Int, val x: Int, val y: Int, val z: Int) : WorldEvent(nanos)
    class Placing(nanos: Long, val entityId: Int, val x: Int, val y: Int, val z: Int) : WorldEvent(nanos)
    class Chat(nanos: Long, val json: String, val actionBar: Boolean) : WorldEvent(nanos)
    class Title(nanos: Long, val titleJson: String?, val subtitleJson: String?) : WorldEvent(nanos)
    class ScoreboardChanged(nanos: Long) : WorldEvent(nanos)
    class ObjectiveChanged(nanos: Long, val name: String, val displayName: String) : WorldEvent(nanos)
    class BossNamed(nanos: Long, val entityId: Int, val nameJson: String) : WorldEvent(nanos)
    class Marker(nanos: Long, val label: String) : WorldEvent(nanos)
}

fun interface WorldEventSink {
    fun onEvent(event: WorldEvent)
}

interface WorldTracker {
    val world: WorldState
    fun reset()
    fun apply(packet: CapturedPacket, events: WorldEventSink? = null)
}
