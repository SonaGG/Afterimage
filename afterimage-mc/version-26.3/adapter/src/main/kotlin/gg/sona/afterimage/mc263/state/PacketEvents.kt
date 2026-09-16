package gg.sona.afterimage.mc263.state

import gg.sona.afterimage.mc263.mixin.EntityEventAccessor
import gg.sona.afterimage.mc263.protocol.McReplayProtocol
import gg.sona.afterimage.mc263.state.ShadowEntity.Companion.toRef
import gg.sona.afterimage.protocol.ServerboundPacket
import gg.sona.afterimage.protocol.SessionMark
import gg.sona.afterimage.world.WorldEvent
import gg.sona.afterimage.world.WorldEventSink
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.*
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.level.block.Block
import java.util.*

object PacketEvents {
    private const val FLAG_USING = 0x10

    fun before(packet: Packet<*>, client: ShadowClient, nanos: Long, sink: WorldEventSink) {
        val local = client.localPlayer
        when (packet) {
            is ClientboundBlockUpdatePacket -> sink.onEvent(WorldEvent.BlockChanged(nanos, packet.pos.x, packet.pos.y, packet.pos.z, Block.getId(packet.blockState), -1))
            is ClientboundSectionBlocksUpdatePacket -> packet.runUpdates { pos, state ->
                sink.onEvent(WorldEvent.BlockChanged(nanos, pos.x, pos.y, pos.z, Block.getId(state), -1))
            }

            is ClientboundSetEntityDataPacket -> {
                val entity = client.entityMap[packet.id()]
                val isLocal = packet.id() == local.entityId
                if ((entity != null && entity.isPlayer) || (entity == null && isLocal)) {
                    val previous = if (isLocal) local.flags else entity!!.flags
                    val flagsId = gg.sona.afterimage.mc263.mixin.EntityDataIdsAccessor.afterimage_sharedFlags().id()
                    val entry = packet.packedItems().firstOrNull { it.id() == flagsId }
                    val now = ((entry?.value() as? Byte)?.toInt() ?: previous) and 0xFF
                    val was = previous and FLAG_USING != 0
                    val using = now and FLAG_USING != 0
                    if (was != using) {
                        val held = if (isLocal) local.held else entity!!.equipment(0)
                        sink.onEvent(WorldEvent.UseItem(nanos, packet.id(), using, held))
                    }
                }
                if (!isLocal && entity != null && isBoss(entity)) {
                    customName(packet.packedItems())?.let { sink.onEvent(WorldEvent.BossNamed(nanos, packet.id(), it)) }
                }
            }

            is ClientboundSetEquipmentPacket -> {
                val entity = client.entityMap[packet.entity] ?: return
                for (pair in packet.slots) {
                    val previous = entity.equipment[pair.first]
                    if (sameItem(previous, pair.second)) continue
                    sink.onEvent(WorldEvent.Equipped(nanos, packet.entity, slotIndex(pair.first), pair.second.toRef()))
                }
            }

            is ClientboundRemoveEntitiesPacket -> for (id in packet.entityIds()) sink.onEvent(WorldEvent.EntityRemoved(nanos, id))
            is ClientboundLoginPacket -> sink.onEvent(WorldEvent.WorldJoined(nanos))
            is ClientboundRespawnPacket -> {
                val key = packet.commonPlayerSpawnInfo().dimension().identifier().toString()
                val dimension = McReplayProtocol.dimensionId(key)
                if (key != client.level.dimensionKey) sink.onEvent(WorldEvent.DimensionChanged(nanos, dimension))
                sink.onEvent(WorldEvent.Respawned(nanos, dimension))
            }

            else -> Unit
        }
    }

    fun after(packet: Packet<*>, client: ShadowClient, nanos: Long, sink: WorldEventSink) {
        val local = client.localPlayer
        when (packet) {
            is ClientboundAddEntityPacket -> {
                val entity = client.entityMap[packet.id]
                val hint = if (entity != null && entity.isProjectile && packet.data > 0) packet.data - 1 else -1
                sink.onEvent(WorldEvent.EntitySpawned(nanos, packet.id, hint))
                if (entity != null && isBoss(entity)) entity.data.values.toList().let { customName(it) }?.let { sink.onEvent(WorldEvent.BossNamed(nanos, packet.id, it)) }
            }

            is ClientboundSwingAnimationPacket -> sink.onEvent(WorldEvent.ArmSwing(nanos, packet.entityId()))
            is ClientboundAnimatePacket -> when (packet.action) {
                ClientboundAnimatePacket.CRITICAL_HIT -> sink.onEvent(WorldEvent.Critical(nanos, packet.id, false))
                ClientboundAnimatePacket.MAGIC_CRITICAL_HIT -> sink.onEvent(WorldEvent.Critical(nanos, packet.id, true))
                else -> Unit
            }

            is ClientboundEntityEventPacket -> {
                val id = (packet as EntityEventAccessor).afterimage_entityId()
                when (packet.eventId) {
                    EntityEvent.DEATH -> sink.onEvent(WorldEvent.EntityDied(nanos, id))
                    else -> Unit
                }
            }

            is ClientboundDamageEventPacket -> if (packet.entityId() != local.entityId) sink.onEvent(WorldEvent.Hurt(nanos, packet.entityId()))
            is ClientboundHurtAnimationPacket -> if (packet.id() != local.entityId) sink.onEvent(WorldEvent.Hurt(nanos, packet.id()))
            is ClientboundSetHealthPacket -> {
                sink.onEvent(WorldEvent.LocalHealth(nanos, packet.health))
                if (packet.health <= 0f) sink.onEvent(WorldEvent.LocalDied(nanos, null))
            }

            is ClientboundPlayerCombatKillPacket -> if (packet.playerId() == local.entityId) sink.onEvent(WorldEvent.LocalDied(nanos, json(packet.message())))
            is ServerboundAttackPacket -> {
                sink.onEvent(WorldEvent.ArmSwing(nanos, local.entityId))
                sink.onEvent(WorldEvent.Attack(nanos, local.entityId, packet.entityId()))
            }

            is ServerboundUseItemOnPacket -> {
                sink.onEvent(WorldEvent.ArmSwing(nanos, local.entityId))
                val hit = packet.hitResult()
                val pos = hit.blockPos.relative(hit.direction)
                sink.onEvent(WorldEvent.Placing(nanos, local.entityId, pos.x, pos.y, pos.z))
            }

            is ServerboundUseItemPacket -> sink.onEvent(WorldEvent.ArmSwing(nanos, local.entityId))
            is ServerboundPlayerActionPacket -> when (packet.action) {
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK -> {
                    sink.onEvent(WorldEvent.ArmSwing(nanos, local.entityId))
                    sink.onEvent(WorldEvent.Digging(nanos, local.entityId, packet.pos.x, packet.pos.y, packet.pos.z))
                }

                else -> Unit
            }

            is ClientboundBlockDestructionPacket -> sink.onEvent(WorldEvent.Digging(nanos, packet.id, packet.pos.x, packet.pos.y, packet.pos.z))
            is ClientboundTakeItemEntityPacket -> {
                val item = client.entityMap[packet.itemId]
                val stack = item?.data?.values?.firstNotNullOfOrNull { it.value() as? net.minecraft.world.item.ItemStack }
                sink.onEvent(WorldEvent.ItemCollected(nanos, packet.playerId, packet.itemId, stack.toRef()))
            }

            is ClientboundUpdateMobEffectPacket -> {
                val id = BuiltInRegistries.MOB_EFFECT.getId(packet.effect.value())
                val label = packet.effect.unwrapKey().map { it.identifier().path.replace('_', ' ') }.orElse("effect $id")
                sink.onEvent(WorldEvent.EffectApplied(nanos, packet.entityId, id, label))
            }

            is ClientboundExplodePacket -> sink.onEvent(WorldEvent.Explosion(nanos, packet.center().x, packet.center().y, packet.center().z, packet.radius(), packet.blockCount()))
            is ClientboundSoundPacket -> sink.onEvent(WorldEvent.Sound(nanos, packet.sound.value().location().toString(), packet.x, packet.y, packet.z, packet.volume))
            is ClientboundPlayerInfoUpdatePacket -> if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                for (entry in packet.entries()) sink.onEvent(WorldEvent.PlayerJoined(nanos, entry.profileId(), entry.profile()?.name ?: ""))
            }

            is ClientboundPlayerInfoRemovePacket -> for (uuid in packet.profileIds()) {
                sink.onEvent(WorldEvent.PlayerLeft(nanos, uuid, client.players.known[uuid]?.name))
            }

            is ClientboundSystemChatPacket -> sink.onEvent(WorldEvent.Chat(nanos, json(packet.content()), packet.overlay()))
            is ClientboundPlayerChatPacket -> sink.onEvent(WorldEvent.Chat(nanos, json(ShadowOverlays.decorate(packet)), false))
            is ClientboundDisguisedChatPacket -> sink.onEvent(WorldEvent.Chat(nanos, json(packet.chatType().decorate(packet.message())), false))
            is ClientboundSetActionBarTextPacket -> sink.onEvent(WorldEvent.Chat(nanos, json(packet.text()), true))

            is ClientboundSetTitleTextPacket -> sink.onEvent(WorldEvent.Title(nanos, json(packet.text()), null))
            is ClientboundSetSubtitleTextPacket -> sink.onEvent(WorldEvent.Title(nanos, null, json(packet.text())))
            is ClientboundSetScorePacket, is ClientboundResetScorePacket, is ClientboundSetPlayerTeamPacket, is ClientboundSetDisplayObjectivePacket -> sink.onEvent(WorldEvent.ScoreboardChanged(nanos))
            is ClientboundSetObjectivePacket -> {
                if (packet.method != ClientboundSetObjectivePacket.METHOD_REMOVE) sink.onEvent(WorldEvent.ObjectiveChanged(nanos, packet.objectiveName, packet.displayName.string))
                sink.onEvent(WorldEvent.ScoreboardChanged(nanos))
            }

            is ClientboundBossEventPacket -> packet.dispatch(object : ClientboundBossEventPacket.Handler {
                override fun add(id: UUID, name: Component, progress: Float, color: net.minecraft.world.BossEvent.BossBarColor, overlay: net.minecraft.world.BossEvent.BossBarOverlay, darkenScreen: Boolean, playMusic: Boolean, createWorldFog: Boolean) {
                    sink.onEvent(WorldEvent.BossNamed(nanos, -1, json(name)))
                }

                override fun updateName(id: UUID, name: Component) {
                    sink.onEvent(WorldEvent.BossNamed(nanos, -1, json(name)))
                }
            })

            else -> Unit
        }
    }

    fun internal(packet: ServerboundPacket, nanos: Long, sink: WorldEventSink) {
        if (packet is SessionMark && packet.kind == SessionMark.USER_MARKER) sink.onEvent(WorldEvent.Marker(nanos, packet.label))
    }

    private fun isBoss(entity: ShadowEntity): Boolean {
        val path = BuiltInRegistries.ENTITY_TYPE.getKey(entity.entityType).path
        return path == "wither" || path == "ender_dragon"
    }

    private fun customName(values: List<net.minecraft.network.syncher.SynchedEntityData.DataValue<*>>): String? {
        for (value in values) {
            val raw = value.value()
            if (raw is Optional<*>) {
                val component = raw.orElse(null) as? Component ?: continue
                return json(component)
            }
        }
        return null
    }

    private fun sameItem(a: net.minecraft.world.item.ItemStack?, b: net.minecraft.world.item.ItemStack?): Boolean {
        if (a == null || a.isEmpty) return b == null || b.isEmpty
        if (b == null || b.isEmpty) return false
        return a.item === b.item
    }

    private fun slotIndex(slot: net.minecraft.world.entity.EquipmentSlot): Int = when (slot) {
        net.minecraft.world.entity.EquipmentSlot.MAINHAND -> 0
        net.minecraft.world.entity.EquipmentSlot.FEET -> 1
        net.minecraft.world.entity.EquipmentSlot.LEGS -> 2
        net.minecraft.world.entity.EquipmentSlot.CHEST -> 3
        net.minecraft.world.entity.EquipmentSlot.HEAD -> 4
        else -> 5
    }

    fun json(component: Component): String = Components.json(component)

    fun literal(text: String): String = "{\"text\":" + escape(text) + "}"

    private fun escape(text: String): String {
        val builder = StringBuilder(text.length + 2)
        builder.append('"')
        for (char in text) {
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (char < ' ') builder.append(String.format("\\u%04x", char.code)) else builder.append(char)
            }
        }
        builder.append('"')
        return builder.toString()
    }

}
