package gg.sona.afterimage.protocol47

import gg.sona.afterimage.net.PackedPosition
import gg.sona.afterimage.protocol.*
import gg.sona.afterimage.protocol47.shadow.ShadowClient
import gg.sona.afterimage.world.EntityKind
import gg.sona.afterimage.world.WorldEvent
import gg.sona.afterimage.world.WorldEventSink

object PacketEvents47 {
    private const val FLAG_USING = 0x10
    private const val ITEM_STACK_INDEX = 10
    private const val CUSTOM_NAME_INDEX = 2
    private const val WITHER = 64
    private const val ENDER_DRAGON = 63
    private const val ACTION_BAR = 2
    private val FACE_X = intArrayOf(0, 0, 0, 0, -1, 1)
    private val FACE_Y = intArrayOf(-1, 1, 0, 0, 0, 0)
    private val FACE_Z = intArrayOf(0, 0, -1, 1, 0, 0)

    fun before(packet: PlayPacket, client: ShadowClient, nanos: Long, sink: WorldEventSink) {
        val local = client.localPlayer
        when (packet) {
            is BlockChange -> sink.onEvent(
                WorldEvent.BlockChanged(nanos, PackedPosition.x(packet.position), PackedPosition.y(packet.position), PackedPosition.z(packet.position), packet.blockState, -1)
            )

            is MultiBlockChange -> {
                val baseX = packet.chunkX shl 4
                val baseZ = packet.chunkZ shl 4
                for ((x, y, z, state) in packet.records) sink.onEvent(WorldEvent.BlockChanged(nanos, baseX + x, y, baseZ + z, state, -1))
            }

            is LocalBlockChange -> sink.onEvent(
                WorldEvent.BlockChanged(nanos, PackedPosition.x(packet.position), PackedPosition.y(packet.position), PackedPosition.z(packet.position), packet.state, local.entityId)
            )

            is EntityMetadata -> {
                val entry = packet.metadata.firstOrNull { it.index == 0 && it.type == MetadataEntry.BYTE }
                if (entry != null) {
                    val entity = client.entities[packet.entityId]
                    val isLocal = packet.entityId == local.entityId
                    if ((entity != null && entity.isPlayer) || (entity == null && isLocal)) {
                        val previous = if (isLocal) local.flagsByte() else entity!!.metadataByte(0)
                        val now = entry.byteValue()
                        val was = previous and FLAG_USING != 0
                        val using = now and FLAG_USING != 0
                        if (was != using) {
                            val held = if (isLocal) local.heldItem else entity!!.equipment[0]
                            sink.onEvent(WorldEvent.UseItem(nanos, packet.entityId, using, held.toRef()))
                        }
                    }
                }
                if (packet.entityId == local.entityId) return
                val boss = client.entities[packet.entityId]?.takeIf { it.kind == EntityKind.MOB && (it.type == WITHER || it.type == ENDER_DRAGON) }
                if (boss != null) customName(packet.metadata)?.let { sink.onEvent(WorldEvent.BossNamed(nanos, packet.entityId, it)) }
            }

            is EntityEquipment -> {
                val entity = client.entities[packet.entityId] ?: return
                if (packet.slot !in 0..4) return
                val previous = entity.equipment[packet.slot]?.id ?: -1
                if (previous == packet.item.id) return
                sink.onEvent(WorldEvent.Equipped(nanos, packet.entityId, packet.slot, packet.item.toRef()))
            }

            is DestroyEntities -> for (id in packet.entityIds) sink.onEvent(WorldEvent.EntityRemoved(nanos, id))
            is JoinGame -> sink.onEvent(WorldEvent.WorldJoined(nanos))
            is Respawn -> {
                if (packet.dimension != client.level.dimension) sink.onEvent(WorldEvent.DimensionChanged(nanos, packet.dimension))
                sink.onEvent(WorldEvent.Respawned(nanos, packet.dimension))
            }

            else -> Unit
        }
    }

    fun after(packet: PlayPacket, client: ShadowClient, nanos: Long, sink: WorldEventSink) {
        val local = client.localPlayer
        when (packet) {
            is SpawnPlayer -> sink.onEvent(WorldEvent.EntitySpawned(nanos, packet.entityId, -1))
            is SpawnMob -> {
                sink.onEvent(WorldEvent.EntitySpawned(nanos, packet.entityId, -1))
                if (packet.type == WITHER || packet.type == ENDER_DRAGON) {
                    customName(packet.metadata)?.let { sink.onEvent(WorldEvent.BossNamed(nanos, packet.entityId, it)) }
                }
            }

            is SpawnObject -> sink.onEvent(WorldEvent.EntitySpawned(nanos, packet.entityId, shooterHint(packet)))
            is SpawnPainting -> sink.onEvent(WorldEvent.EntitySpawned(nanos, packet.entityId, -1))
            is SpawnExperienceOrb -> sink.onEvent(WorldEvent.EntitySpawned(nanos, packet.entityId, -1))
            is SpawnGlobalEntity -> sink.onEvent(WorldEvent.EntitySpawned(nanos, packet.entityId, -1))
            ClientArmSwing -> sink.onEvent(WorldEvent.ArmSwing(nanos, local.entityId))
            is Animation -> when (packet.animation) {
                Animation.SWING_ARM -> sink.onEvent(WorldEvent.ArmSwing(nanos, packet.entityId))
                Animation.CRITICAL_EFFECT -> sink.onEvent(WorldEvent.Critical(nanos, packet.entityId, false))
                Animation.MAGIC_CRITICAL_EFFECT -> sink.onEvent(WorldEvent.Critical(nanos, packet.entityId, true))
                Animation.EAT_FOOD -> sink.onEvent(WorldEvent.Eating(nanos, packet.entityId))
                else -> Unit
            }

            is EntityStatus -> when (packet.status) {
                EntityStatus.HURT -> if (packet.entityId != local.entityId) sink.onEvent(WorldEvent.Hurt(nanos, packet.entityId))
                EntityStatus.DEAD -> sink.onEvent(WorldEvent.EntityDied(nanos, packet.entityId))
                else -> Unit
            }

            is CombatEvent -> if (packet.event == CombatEvent.ENTITY_DEAD && packet.playerId == local.entityId) {
                sink.onEvent(WorldEvent.LocalDied(nanos, packet.messageJson))
            }

            is UpdateHealth -> {
                sink.onEvent(WorldEvent.LocalHealth(nanos, packet.health))
                if (packet.health <= 0f) sink.onEvent(WorldEvent.LocalDied(nanos, null))
            }

            is UseEntity -> if (packet.type == UseEntity.ATTACK) sink.onEvent(WorldEvent.Attack(nanos, local.entityId, packet.targetId))
            is CollectItem -> {
                val item = client.entities[packet.collectedId]
                val stack = item?.metadata?.get(ITEM_STACK_INDEX)?.value as? ItemStack
                sink.onEvent(WorldEvent.ItemCollected(nanos, packet.collectorId, packet.collectedId, stack.toRef()))
            }

            is EntityEffect -> sink.onEvent(WorldEvent.EffectApplied(nanos, packet.entityId, packet.effectId, Names47.INSTANCE.effectLabel(packet.effectId)))
            is Explosion -> sink.onEvent(WorldEvent.Explosion(nanos, packet.x.toDouble(), packet.y.toDouble(), packet.z.toDouble(), packet.radius, packet.affectedBlocks.size))
            is SoundEffect -> sink.onEvent(WorldEvent.Sound(nanos, packet.name, packet.x / 8.0, packet.y / 8.0, packet.z / 8.0, packet.volume))
            is PlayerListItem -> when (packet.action) {
                PlayerListItem.ADD_PLAYER -> for (entry in packet.entries) sink.onEvent(WorldEvent.PlayerJoined(nanos, entry.uuid, entry.name ?: ""))
                PlayerListItem.REMOVE_PLAYER -> for (entry in packet.entries) {
                    sink.onEvent(WorldEvent.PlayerLeft(nanos, entry.uuid, client.players.knownProfiles[entry.uuid]?.name))
                }

                else -> Unit
            }

            is BlockBreakAnimation -> sink.onEvent(
                WorldEvent.Digging(nanos, packet.entityId, PackedPosition.x(packet.position), PackedPosition.y(packet.position), PackedPosition.z(packet.position))
            )

            is PlayerDigging -> if (packet.status == PlayerDigging.START_DIGGING || packet.status == PlayerDigging.FINISH_DIGGING) {
                sink.onEvent(
                    WorldEvent.Digging(nanos, local.entityId, PackedPosition.x(packet.position), PackedPosition.y(packet.position), PackedPosition.z(packet.position))
                )
            }

            is PlayerBlockPlacement -> if (packet.face in 0..5) {
                sink.onEvent(
                    WorldEvent.Placing(
                        nanos,
                        local.entityId,
                        PackedPosition.x(packet.position) + FACE_X[packet.face],
                        PackedPosition.y(packet.position) + FACE_Y[packet.face],
                        PackedPosition.z(packet.position) + FACE_Z[packet.face],
                    )
                )
            }

            is ChatMessage -> sink.onEvent(WorldEvent.Chat(nanos, packet.json, packet.position == ACTION_BAR))
            is Title -> when (packet.action) {
                Title.SET_TITLE -> sink.onEvent(WorldEvent.Title(nanos, packet.textJson, null))
                Title.SET_SUBTITLE -> sink.onEvent(WorldEvent.Title(nanos, null, packet.textJson))
                else -> Unit
            }

            is UpdateScore -> sink.onEvent(WorldEvent.ScoreboardChanged(nanos))
            is Teams -> sink.onEvent(WorldEvent.ScoreboardChanged(nanos))
            is DisplayScoreboard -> sink.onEvent(WorldEvent.ScoreboardChanged(nanos))
            is ScoreboardObjective -> {
                if (packet.mode != ScoreboardObjective.REMOVE) {
                    sink.onEvent(WorldEvent.ObjectiveChanged(nanos, packet.name, packet.displayName ?: packet.name))
                }
                sink.onEvent(WorldEvent.ScoreboardChanged(nanos))
            }

            is SessionMark -> if (packet.kind == SessionMark.USER_MARKER) sink.onEvent(WorldEvent.Marker(nanos, packet.label))
            else -> Unit
        }
    }

    private fun shooterHint(packet: SpawnObject): Int = when (packet.type) {
        60 -> if (packet.data > 0) packet.data - 1 else -1
        63, 64, 66, 90 -> if (packet.data > 0) packet.data else -1
        else -> -1
    }

    private fun customName(metadata: List<MetadataEntry>): String? =
        metadata.firstOrNull { it.index == CUSTOM_NAME_INDEX && it.type == MetadataEntry.STRING }?.value as? String
}
