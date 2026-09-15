package gg.sona.afterimage.mc26.state

import com.mojang.datafixers.util.Pair
import gg.sona.afterimage.mc26.mixin.EntityEventInvoker
import gg.sona.afterimage.mc26.mixin.RotateHeadInvoker
import gg.sona.afterimage.mc26.mixin.SetEntityLinkInvoker
import gg.sona.afterimage.mc26.mixin.SetPlayerTeamInvoker
import gg.sona.afterimage.mc26.mixin.UpdateAttributesInvoker
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.protocol.CameraFrame
import gg.sona.afterimage.protocol.InternalCodec
import gg.sona.afterimage.protocol.LocalPose
import gg.sona.afterimage.protocol.LocalTarget
import net.minecraft.world.item.ItemStack
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket
import io.netty.buffer.Unpooled
import net.minecraft.core.Holder
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket
import net.minecraft.network.protocol.game.ClientboundGameEventPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket
import net.minecraft.network.protocol.game.ClientboundResetScorePacket
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import java.util.*
import kotlin.math.floor

object StateDiff26 {
    fun canDiff(from: ShadowClient26, to: ShadowClient26): Boolean =
        from.joined && to.joined && from.level.dimensionKey == to.level.dimensionKey &&
                from.localPlayer.entityId == to.localPlayer.entityId && from.configuration.size == to.configuration.size

    fun packets(from: ShadowClient26, to: ShadowClient26, nanos: Long, freshEntities: Boolean = false): List<CapturedPacket> {
        val out = ArrayList<Packet<*>>(64)
        diffPlayers(from, to, out)
        diffLevelMeta(from, to, nanos, out)
        diffChunks(from, to, out)
        diffEntities(from, to, nanos, out, freshEntities)
        diffScoreboard(from, to, out)
        diffLocalPlayer(from, to, nanos, out)
        diffExtras(from, to, out)
        val result = ArrayList<CapturedPacket>(out.size + 8)
        val overlays = ArrayList<Packet<*>>(4)
        val reset = to.overlays.snapshot(nanos, to.codecs.registries, overlays)
        for (packet in overlays) to.codecs.encode(packet, nanos)?.let { result += it }
        result += InternalCodec.encode(reset, nanos)
        for (packet in out) to.codecs.encode(packet, nanos)?.let { result += it }
        val target = to.localPlayer
        if (target.hasPosition) result += InternalCodec.encode(LocalPose(target.x, target.y, target.z, target.yaw, target.pitch), nanos)
        target.cameraFrames.before(nanos)?.let { result += InternalCodec.encode(CameraFrame(it.modelView, it.fov, it.position, it.hand), nanos) }
        if (from.localPlayer.screen != target.screen) result += InternalCodec.encode(target.screen, nanos)
        if (from.localPlayer.target != target.target) result += InternalCodec.encode(target.target ?: LocalTarget.NONE, nanos)
        return result
    }

    private fun diffPlayers(from: ShadowClient26, to: ShadowClient26, out: MutableList<Packet<*>>) {
        val removed = from.players.entries.keys.filter { it !in to.players.entries }
        if (removed.isNotEmpty()) out += ClientboundPlayerInfoRemovePacket(removed)
        val added = to.players.entries.values.filter { it.uuid !in from.players.entries }
        if (added.isNotEmpty()) out += ShadowPlayers26.addPacket(added.map { it.entry })
        val changed = to.players.entries.values.filter { player ->
            val previous = from.players.entries[player.uuid]
            previous != null && previous.entry != player.entry
        }
        for (player in changed) {
            val entries = listOf(player.entry)
            out += ShadowPlayers26.updatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE, entries)
            out += ShadowPlayers26.updatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED, entries)
            out += ShadowPlayers26.updatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY, entries)
            out += ShadowPlayers26.updatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, entries)
            out += ShadowPlayers26.updatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT, entries)
            out += ShadowPlayers26.updatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER, entries)
        }
        if (from.players.tabList != to.players.tabList) to.players.tabList?.let { out += it }
    }

    private fun diffLevelMeta(from: ShadowClient26, to: ShadowClient26, nanos: Long, out: MutableList<Packet<*>>) {
        val source = from.level
        val target = to.level
        for (kind in ShadowLevel26.SINGLETON_ORDER) {
            val packet = target.singletons[kind] ?: continue
            if (!packet.same(source.singletons[kind])) out += packet.packet
        }
        SnapshotEncoder26.currentTime(to, nanos)?.let { out += it }
        if (source.raining != target.raining) {
            out += ClientboundGameEventPacket(if (target.raining) ClientboundGameEventPacket.START_RAINING else ClientboundGameEventPacket.STOP_RAINING, 0f)
        }
        for ((type, packet) in target.gameEvents) {
            if (!packet.same(source.gameEvents[type])) out += packet.packet
        }
        val border = target.borderInitialize
        if (border != null && !border.same(source.borderInitialize)) {
            out += border.packet
            for (update in target.borderUpdates.values) out += update.packet
        } else {
            for ((kind, packet) in target.borderUpdates) if (!packet.same(source.borderUpdates[kind])) out += packet.packet
        }
    }

    private fun diffChunks(from: ShadowClient26, to: ShadowClient26, out: MutableList<Packet<*>>) {
        val source = from.level
        val target = to.level
        for (key in source.chunks.keys) {
            if (target.chunks[key] == null) out += ClientboundForgetLevelChunkPacket(ChunkPos.unpack(key))
        }
        for ((key, chunk) in target.chunks) {
            val previous = source.chunks[key]
            if (previous == null || !previous.same(chunk)) chunk.emit(out)
        }
    }

    private fun diffEntities(from: ShadowClient26, to: ShadowClient26, nanos: Long, out: MutableList<Packet<*>>, fresh: Boolean) {
        val removed = ArrayList<Int>()
        from.entityMap.forEach { id, previous ->
            val current = to.entityMap[id]
            if ((fresh && previous.living) || current == null || current.spawnHash != previous.spawnHash || !current.visibleAt(nanos)) removed += id
        }
        if (removed.isNotEmpty()) out += ClientboundRemoveEntitiesPacket(*removed.toIntArray())
        val spawned = ArrayList<ShadowEntity26>()
        to.entityMap.forEach { id, current ->
            if (!current.visibleAt(nanos)) return@forEach
            val previous = from.entityMap[id]?.takeUnless { fresh && it.living }
            if (previous == null || previous.spawnHash != current.spawnHash) spawned += current else updateEntity(to.codecs, previous, current, nanos, out)
        }
        spawned.sortBy { it.id }
        for (entity in spawned) entity.spawnPackets(out, nanos)
        for (entity in spawned) entity.attachmentPackets(out)
    }

    private fun updateEntity(codecs: Codecs26, previous: ShadowEntity26, current: ShadowEntity26, nanos: Long, out: MutableList<Packet<*>>) {
        val id = current.id
        if (previous.x != current.x || previous.y != current.y || previous.z != current.z || previous.yawDegrees != current.yawDegrees || previous.pitchDegrees != current.pitchDegrees) {
            out += ClientboundTeleportEntityPacket(
                id,
                PositionMoveRotation(Vec3(current.x, current.y, current.z), current.motion?.movement() ?: Vec3.ZERO, current.yawDegrees, current.pitchDegrees),
                EnumSet.noneOf(Relative::class.java),
                current.onGround,
            )
        }
        if (previous.headYawDegrees != current.headYawDegrees) out += rotateHead(id, current.headYawDegrees)
        if (!sameData(codecs, id, previous.data, current.data)) out += ClientboundSetEntityDataPacket(id, ArrayList(current.data.values))
        if (!sameEquipment(previous.equipment, current.equipment)) {
            out += ClientboundSetEquipmentPacket(id, ShadowEntity26.slotOrder().map { slot -> Pair.of(slot, current.equipment[slot] ?: net.minecraft.world.item.ItemStack.EMPTY) })
        }
        if (previous.attributes != current.attributes && current.attributes.isNotEmpty()) out += UpdateAttributesInvoker.afterimage_create(id, ArrayList(current.attributes.values))
        for ((effect, shadowEffect) in current.effects) if (!shadowEffect.same(previous.effects[effect])) shadowEffect.packetAt(id, nanos)?.let { out += it }
        for ((effect, _) in previous.effects) if (!current.effects.containsKey(effect)) {
            @Suppress("UNCHECKED_CAST")
            out += ClientboundRemoveMobEffectPacket(id, effect as net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>)
        }
        if (!codecs.same(previous.passengers, current.passengers)) current.passengers?.let { out += it }
        if (!codecs.same(previous.motion, current.motion)) current.motion?.let { out += it }
        if (!codecs.same(previous.link, current.link)) out += current.link ?: ClientboundSetEntityLinkPacket26.unlink(id)
        if (!codecs.same(previous.projectilePower, current.projectilePower)) current.projectilePower?.let { out += it }
        if (!previous.dead && current.dead) out += entityEvent(id, EntityEvent.DEATH)
    }

    private fun rotateHead(id: Int, headYaw: Float): Packet<*> {
        val buffer = FriendlyByteBuf(Unpooled.buffer(8))
        try {
            buffer.writeVarInt(id)
            buffer.writeByte(floor(headYaw * 256.0 / 360.0).toInt())
            return RotateHeadInvoker.afterimage_read(buffer)
        } finally {
            buffer.release()
        }
    }

    private fun entityEvent(id: Int, event: Byte): Packet<*> {
        val buffer = FriendlyByteBuf(Unpooled.buffer(8))
        try {
            buffer.writeInt(id)
            buffer.writeByte(event.toInt())
            return EntityEventInvoker.afterimage_read(buffer)
        } finally {
            buffer.release()
        }
    }

    private fun diffScoreboard(from: ShadowClient26, to: ShadowClient26, out: MutableList<Packet<*>>) {
        val source = from.scoreboard
        val target = to.scoreboard
        for ((name, objective) in source.objectives) {
            if (target.objectives[name] == null) out += ShadowScoreboard26.objectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE)
        }
        for ((name, objective) in target.objectives) {
            val previous = source.objectives[name]
            if (previous == null) {
                out += ShadowScoreboard26.objectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD)
                out += objective.scorePackets.values
                continue
            }
            if (!sameObjective(previous.packet, objective.packet)) out += ShadowScoreboard26.objectivePacket(objective, ClientboundSetObjectivePacket.METHOD_CHANGE)
            for (owner in previous.scorePackets.keys) if (!objective.scorePackets.containsKey(owner)) out += ClientboundResetScorePacket(owner, name)
            for ((owner, packet) in objective.scorePackets) if (previous.scorePackets[owner] !== packet) out += packet
        }
        for ((slot, name) in source.display) {
            if (target.display[slot] == null) out += ClientboundSetDisplayObjectivePacket(slot, null)
        }
        for ((slot, name) in target.display) {
            if (source.display[slot] != name) target.objectives[name]?.let { out += ClientboundSetDisplayObjectivePacket(slot, ShadowScoreboard26.dummyObjective(it.packet)) }
        }
        for (name in source.teams.keys) {
            if (target.teams[name] == null) out += SetPlayerTeamInvoker.afterimage_create(name, ShadowTeam26.METHOD_REMOVE, Optional.empty(), emptyList())
        }
        for ((name, team) in target.teams) {
            val previous = source.teams[name]
            if (previous == null) {
                out += team.toPacket()
                continue
            }
            if (previous.parameters != team.parameters) out += SetPlayerTeamInvoker.afterimage_create(name, ShadowTeam26.METHOD_CHANGE, Optional.ofNullable(team.parameters), emptyList())
            val left = previous.members.filter { it !in team.members }
            if (left.isNotEmpty()) out += SetPlayerTeamInvoker.afterimage_create(name, ShadowTeam26.METHOD_LEAVE, Optional.empty(), left)
            val joined = team.members.filter { it !in previous.members }
            if (joined.isNotEmpty()) out += SetPlayerTeamInvoker.afterimage_create(name, ShadowTeam26.METHOD_JOIN, Optional.empty(), joined)
        }
    }

    private fun diffLocalPlayer(from: ShadowClient26, to: ShadowClient26, nanos: Long, out: MutableList<Packet<*>>) {
        val source = from.localPlayer
        val target = to.localPlayer
        val codecs = to.codecs
        if (!codecs.same(source.abilities, target.abilities)) target.abilities?.let { out += it }
        if (source.heldSlot != target.heldSlot) out += ClientboundSetHeldSlotPacket(target.heldSlot)
        if (!codecs.same(source.healthPacket, target.healthPacket)) target.healthPacket?.let { out += it }
        if (!codecs.same(source.experience, target.experience)) target.experience?.let { out += it }
        if (!ItemStack.listMatches(source.inventory, target.inventory) || !ItemStack.matches(source.carried, target.carried)) {
            out += ClientboundContainerSetContentPacket(0, target.inventoryStateId, ArrayList(target.inventory), target.carried)
        }
        if (!sameData(codecs, target.entityId, source.data, target.data) && target.data.isNotEmpty()) out += ClientboundSetEntityDataPacket(target.entityId, ArrayList(target.data.values))
        if (source.attributes != target.attributes && target.attributes.isNotEmpty()) out += UpdateAttributesInvoker.afterimage_create(target.entityId, ArrayList(target.attributes.values))
        for ((effect, shadowEffect) in target.effects) if (!shadowEffect.same(source.effects[effect])) shadowEffect.packetAt(target.entityId, nanos)?.let { out += it }
        for ((effect, _) in source.effects) if (!target.effects.containsKey(effect)) {
            @Suppress("UNCHECKED_CAST")
            out += ClientboundRemoveMobEffectPacket(target.entityId, effect as Holder<MobEffect>)
        }
        if (!codecs.same(source.camera, target.camera)) target.camera?.let { out += it }
        if (!codecs.same(source.postEffects, target.postEffects)) target.postEffects?.let { out += it }
        if (source.gameType != target.gameType) out += ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, target.gameType.id.toFloat())
        if (target.hasPosition) out += to.currentPositionPacket()
    }

    private fun sameObjective(a: ClientboundSetObjectivePacket, b: ClientboundSetObjectivePacket): Boolean =
        a.displayName == b.displayName && a.renderType == b.renderType && a.numberFormat == b.numberFormat

    private fun sameList(a: List<Retained<*>>, b: List<Retained<*>>): Boolean = a.size == b.size && a.indices.all { a[it].hash == b[it].hash }

    private fun diffExtras(from: ShadowClient26, to: ShadowClient26, out: MutableList<Packet<*>>) {
        for (id in from.level.bossBars.keys) if (!to.level.bossBars.containsKey(id)) out += ClientboundBossEventPacket.createRemovePacket(id)
        for ((id, packets) in to.level.bossBars) {
            val previous = from.level.bossBars[id]
            if (previous == null || !sameList(previous, packets)) for (packet in packets) out += packet.packet
        }
        for ((id, packets) in to.level.maps) {
            val previous = from.level.maps[id]
            if (previous == null || !sameList(previous, packets)) for (packet in packets) out += packet.packet
        }
        for ((id, waypoint) in from.level.waypoints) {
            if (!to.level.waypoints.containsKey(id)) waypoint.packet.waypoint().id().left().ifPresent { out += ClientboundTrackedWaypointPacket.removeWaypoint(it) }
        }
        for ((id, waypoint) in to.level.waypoints) if (!waypoint.same(from.level.waypoints[id])) out += waypoint.packet
        for (id in from.level.resourcePacks.keys) if (!to.level.resourcePacks.containsKey(id)) out += ClientboundResourcePackPopPacket(Optional.of(id))
        for ((id, pack) in to.level.resourcePacks) if (!pack.same(from.level.resourcePacks[id])) out += pack.packet
    }
}

private fun sameData(codecs: Codecs26, id: Int, a: Map<Int, SynchedEntityData.DataValue<*>>, b: Map<Int, SynchedEntityData.DataValue<*>>): Boolean {
    if (a.size != b.size || a.keys != b.keys) return false
    return codecs.same(ClientboundSetEntityDataPacket(id, ArrayList(a.values)), ClientboundSetEntityDataPacket(id, ArrayList(b.values)))
}

private fun sameEquipment(a: Map<net.minecraft.world.entity.EquipmentSlot, ItemStack>, b: Map<net.minecraft.world.entity.EquipmentSlot, ItemStack>): Boolean {
    for (slot in ShadowEntity26.slotOrder()) {
        if (!ItemStack.matches(a[slot] ?: ItemStack.EMPTY, b[slot] ?: ItemStack.EMPTY)) return false
    }
    return true
}

object ClientboundSetEntityLinkPacket26 {
    fun unlink(id: Int): Packet<*> {
        val buffer = FriendlyByteBuf(Unpooled.buffer(8))
        try {
            buffer.writeInt(id)
            buffer.writeInt(0)
            return SetEntityLinkInvoker.afterimage_read(buffer)
        } finally {
            buffer.release()
        }
    }
}
