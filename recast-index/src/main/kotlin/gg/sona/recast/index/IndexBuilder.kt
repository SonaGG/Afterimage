package gg.sona.recast.index

import gg.sona.recast.core.collect.IntObjectMap
import gg.sona.recast.core.time.Nanos
import gg.sona.recast.flashback.ChatText
import gg.sona.recast.flashback.EventDeriver
import gg.sona.recast.flashback.SemanticEvent
import gg.sona.recast.flashback.SemanticEventListener
import gg.sona.recast.net.PackedPosition
import gg.sona.recast.protocol.*
import gg.sona.recast.replay.source.ReplaySource
import gg.sona.recast.replay.state.shadow.EntityKind
import gg.sona.recast.replay.state.shadow.RecorderIdentity
import gg.sona.recast.replay.state.shadow.ShadowClient
import gg.sona.recast.replay.state.shadow.ShadowEntity
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class IndexBuilder(
    private val source: ReplaySource,
    identity: RecorderIdentity,
    private val tickNanos: Long = Nanos.PER_TICK,
) {
    @Volatile
    var progress: Double = 0.0
        private set

    private val cancelled = AtomicBoolean(false)
    private val shadow = ShadowClient(identity)
    private val deriver = EventDeriver(shadow)

    private val open = IntObjectMap<TrackBuilder>(256)
    private val closed = ArrayList<EntityTrack>()
    private val events = EventsBuilder()
    private val blocks = BlocksBuilder()
    private val dimensions = IntColumn(4096)

    private var sampled = 0
    private var localTrack: TrackBuilder? = null

    private val lastAttacker = IntObjectMap<Attack>()
    private val lastSwing = IntObjectMap<Long>()
    private val recentAttacks = ArrayDeque<Attack>()
    private val projectileEnds = ArrayDeque<ProjectileEnd>()
    private val explosions = ArrayDeque<Blast>()
    private val diggers = HashMap<Long, Digger>()
    private var lastHealth = Float.NaN
    private val idByName = HashMap<String, Int>()
    private val lastDeath = IntObjectMap<Long>()
    private var recorderDeathNanos = Long.MIN_VALUE

    fun cancel() = cancelled.set(true)

    fun build(): ReplayIndex? {
        deriver.listeners.add(SemanticEventListener { event -> semantic(event) })
        val startNanos = source.startNanos
        val endNanos = source.endNanos
        var nextTick = startNanos
        val duration = maxOf(1L, endNanos - startNanos)
        for (segment in source.segments) {
            if (cancelled.get()) return null
            if (segment.isSnapshot) continue
            for (packet in source.packets(segment.index)) {
                val nanos = packet.timestampNanos
                while (nextTick <= nanos) {
                    sample()
                    nextTick += tickNanos
                }
                val decoded = PacketCodec.decode(packet) ?: continue
                before(decoded, nanos)
                shadow.apply(decoded, nanos)
                after(decoded, nanos)
                deriver.derive(decoded, nanos)
            }
            progress = ((segment.endNanos - startNanos).toDouble() / duration).coerceIn(0.0, 0.99)
        }
        while (nextTick <= endNanos) {
            sample()
            nextTick += tickNanos
        }
        open.forEach { _, track -> track.close(sampled - 1)?.let { closed += it } }
        open.clear()
        localTrack?.close(sampled - 1)?.let { closed += it }
        localTrack = null
        closed.sortWith(compareBy({ it.firstTick }, { it.entityId }))
        progress = 1.0
        return ReplayIndex(
            startNanos,
            endNanos,
            tickNanos,
            sampled,
            closed,
            events.build(),
            blocks.build(),
            dimensions.toArray()
        )
    }

    private fun sample() {
        val tick = sampled
        dimensions.add(shadow.world.dimension)
        val local = shadow.localPlayer
        if (local.hasPosition) {
            val track = localTrack?.takeIf { it.entityId == local.entityId } ?: TrackBuilder(
                local.entityId,
                EntityKind.PLAYER,
                0,
                local.uuid,
                local.name,
                true,
                -1,
                tick
            ).also {
                localTrack?.close(tick - 1)?.let { done -> closed += done }
                localTrack = it
                local.name?.let { name -> idByName[name.lowercase()] = local.entityId }
            }
            track.push(
                local.x,
                local.y,
                local.z,
                local.yaw,
                local.pitch,
                local.yaw,
                local.health,
                local.heldItem.id,
                local.equipmentItem(1).id,
                local.equipmentItem(2).id,
                local.equipmentItem(3).id,
                local.equipmentItem(4).id,
                (local.flagsByte() and 0x3F) or (if (local.onGround) EntityTrack.FLAG_ON_GROUND else 0),
                local.vehicleId
            )
        } else {
            localTrack?.close(tick - 1)?.let { closed += it }
            localTrack = null
        }
        var stale: ArrayList<Int>? = null
        open.forEach { id, track ->
            if (!track.sampleFrom(shadow)) (stale ?: ArrayList<Int>().also { stale = it }).add(id)
        }
        stale?.forEach { id -> open.remove(id)?.close(tick - 1)?.let { closed += it } }
        sampled++
    }

    private fun before(packet: PlayPacket, nanos: Long) {
        when (packet) {
            is BlockChange -> blockChange(
                nanos,
                PackedPosition.x(packet.position),
                PackedPosition.y(packet.position),
                PackedPosition.z(packet.position),
                packet.blockState,
                -1
            )

            is MultiBlockChange -> {
                val baseX = packet.chunkX shl 4
                val baseZ = packet.chunkZ shl 4
                for ((x, y, z, state) in packet.records) blockChange(nanos, baseX + x, y, baseZ + z, state, -1)
            }

            is LocalBlockChange -> blockChange(
                nanos,
                PackedPosition.x(packet.position),
                PackedPosition.y(packet.position),
                PackedPosition.z(packet.position),
                packet.state,
                shadow.localPlayer.entityId
            )

            is EntityMetadata -> useFlag(packet, nanos)
            is EntityEquipment -> {
                val entity = shadow.entities[packet.entityId] ?: return
                if (packet.slot !in 0..4) return
                val previous = entity.equipment[packet.slot]?.id ?: -1
                if (previous == packet.item.id) return
                events.add(
                    nanos, sampled, IndexEventKind.EQUIP, packet.entityId, -1,
                    Double.NaN, Double.NaN, Double.NaN, packet.slot.toFloat(),
                    if (packet.item.isEmpty) null else Items.label(packet.item.id)
                )
            }

            is DestroyEntities -> for (id in packet.entityIds) {
                val entity = shadow.entities[id] ?: continue
                if (entity.kind == EntityKind.OBJECT && entity.type in EntityNames.PROJECTILES) {
                    val shooter = open[id]?.shooterId ?: -1
                    events.add(
                        nanos, sampled, IndexEventKind.PROJECTILE_END, id, shooter,
                        entity.x, entity.y, entity.z, 0f, EntityNames.OBJECTS[entity.type]
                    )
                    projectileEnds.addLast(ProjectileEnd(nanos, shooter, entity.x, entity.y, entity.z))
                    if (projectileEnds.size > 64) projectileEnds.removeFirst()
                }
                open.remove(id)?.close(sampled - 1)?.let { closed += it }
            }

            is JoinGame -> {
                lastAttacker.clear()
                lastHealth = Float.NaN
                open.forEach { _, track -> track.close(sampled - 1)?.let { closed += it } }
                open.clear()
            }

            is Respawn -> if (packet.dimension != shadow.world.dimension) {
                open.forEach { _, track -> track.close(sampled - 1)?.let { closed += it } }
                open.clear()
                events.add(
                    nanos, sampled, IndexEventKind.DIMENSION, shadow.localPlayer.entityId, -1,
                    Double.NaN, Double.NaN, Double.NaN, packet.dimension.toFloat(), dimensionName(packet.dimension)
                )
            }

            else -> Unit
        }
    }

    private fun after(packet: PlayPacket, nanos: Long) {
        when (packet) {
            is SpawnPlayer -> spawned(packet.entityId, -1)
            is SpawnMob -> spawned(packet.entityId, -1)
            is SpawnObject -> {
                val entity = shadow.entities[packet.entityId] ?: return
                val shooter = if (packet.type in EntityNames.PROJECTILES) shooterOf(packet, entity) else -1
                spawned(packet.entityId, shooter)
                if (packet.type in EntityNames.PROJECTILES) events.add(
                    nanos, sampled, IndexEventKind.PROJECTILE_SPAWN, packet.entityId, shooter,
                    entity.x, entity.y, entity.z, 0f, EntityNames.OBJECTS[packet.type]
                )
            }

            is SpawnPainting -> spawned(packet.entityId, -1)
            is SpawnExperienceOrb -> spawned(packet.entityId, -1)
            is SpawnGlobalEntity -> spawned(packet.entityId, -1)

            is ClientArmSwing -> {
                val local = shadow.localPlayer.entityId
                lastSwing.put(local, nanos)
                events.add(
                    nanos,
                    sampled,
                    IndexEventKind.SWING,
                    local,
                    -1,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    0f,
                    null
                )
            }

            is Animation -> when (packet.animation) {
                Animation.SWING_ARM -> {
                    lastSwing.put(packet.entityId, nanos)
                    events.add(
                        nanos,
                        sampled,
                        IndexEventKind.SWING,
                        packet.entityId,
                        -1,
                        Double.NaN,
                        Double.NaN,
                        Double.NaN,
                        0f,
                        null
                    )
                }

                Animation.CRITICAL_EFFECT, Animation.MAGIC_CRITICAL_EFFECT -> events.add(
                    nanos, sampled, IndexEventKind.CRIT, packet.entityId, lastAttacker[packet.entityId]?.attacker ?: -1,
                    Double.NaN, Double.NaN, Double.NaN, 0f,
                    if (packet.animation == Animation.MAGIC_CRITICAL_EFFECT) "sharpness" else null
                )

                Animation.EAT_FOOD -> events.add(
                    nanos, sampled, IndexEventKind.EAT, packet.entityId, -1, Double.NaN, Double.NaN, Double.NaN, 0f,
                    shadow.entities[packet.entityId]?.equipment?.get(0)?.takeIf { !it.isEmpty }
                        ?.let { Items.label(it.id) }
                )
            }

            is EntityStatus -> if (packet.status == EntityStatus.HURT && packet.entityId != shadow.localPlayer.entityId) hurt(
                packet.entityId,
                nanos,
                Float.NaN
            )

            is UpdateHealth -> {
                val previous = lastHealth
                lastHealth = packet.health
                if (!previous.isNaN() && packet.health < previous && packet.health > 0f) hurt(
                    shadow.localPlayer.entityId,
                    nanos,
                    previous - packet.health
                )
            }

            is UseEntity -> if (packet.type == UseEntity.ATTACK) {
                val attacker = shadow.localPlayer.entityId
                events.add(
                    nanos,
                    sampled,
                    IndexEventKind.ATTACK,
                    attacker,
                    packet.targetId,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    0f,
                    null
                )
                remember(Attack(nanos, attacker, packet.targetId))
            }

            is CollectItem -> {
                val item = shadow.entities[packet.collectedId]
                val stack = item?.metadata?.get(ITEM_STACK_INDEX)?.value as? ItemStack
                events.add(
                    nanos, sampled, IndexEventKind.PICKUP, packet.collectorId, packet.collectedId,
                    item?.x ?: Double.NaN, item?.y ?: Double.NaN, item?.z ?: Double.NaN,
                    (stack?.count ?: 0).toFloat(),
                    stack?.let { Items.label(it.id) } ?: item?.let { EntityNames.of(it.kind, it.type, it.id) }
                )
            }

            is EntityEffect -> events.add(
                nanos, sampled, IndexEventKind.EFFECT, packet.entityId, -1, Double.NaN, Double.NaN, Double.NaN,
                packet.effectId.toFloat(), Items.EFFECTS[packet.effectId] ?: "effect ${packet.effectId}"
            )

            is Explosion -> {
                val x = packet.x.toDouble()
                val y = packet.y.toDouble()
                val z = packet.z.toDouble()
                var shooter = nearestProjectileShooter(x, y, z)
                if (shooter < 0) for (end in projectileEnds.asReversed()) {
                    if (nanos - end.nanos > EXPLOSION_MATCH_WINDOW) break
                    if (distanceSquared(
                            end.x,
                            end.y,
                            end.z,
                            x,
                            y,
                            z
                        ) <= EXPLOSION_MATCH_RADIUS * EXPLOSION_MATCH_RADIUS
                    ) {
                        shooter = end.shooter
                        break
                    }
                }
                events.add(
                    nanos, sampled, IndexEventKind.EXPLOSION, -1, shooter, x, y, z, packet.radius,
                    if (packet.affectedBlocks.isEmpty()) null else "${packet.affectedBlocks.size} blocks"
                )
                explosions.addLast(Blast(nanos, shooter, x, y, z, packet.radius.toDouble()))
                if (explosions.size > 32) explosions.removeFirst()
            }

            is SoundEffect -> events.add(
                nanos, sampled, IndexEventKind.SOUND, -1, -1,
                packet.x / 8.0, packet.y / 8.0, packet.z / 8.0, packet.volume, packet.name
            )

            is PlayerListItem -> when (packet.action) {
                PlayerListItem.ADD_PLAYER -> for ((_, name) in packet.entries) events.add(
                    nanos, sampled, IndexEventKind.JOIN, -1, -1, Double.NaN, Double.NaN, Double.NaN, 0f, name
                )

                PlayerListItem.REMOVE_PLAYER -> for ((uuid) in packet.entries) events.add(
                    nanos, sampled, IndexEventKind.LEAVE, -1, -1, Double.NaN, Double.NaN, Double.NaN, 0f,
                    shadow.players.knownProfiles[uuid]?.name
                )
            }

            is BlockBreakAnimation -> diggers[packet.position] = Digger(packet.entityId, nanos)
            is PlayerDigging -> if (packet.status == PlayerDigging.START_DIGGING || packet.status == PlayerDigging.FINISH_DIGGING) {
                diggers[packet.position] = Digger(shadow.localPlayer.entityId, nanos)
            }

            is PlayerBlockPlacement -> if (packet.face in 0..5) {
                val x = PackedPosition.x(packet.position) + FACE_X[packet.face]
                val y = PackedPosition.y(packet.position) + FACE_Y[packet.face]
                val z = PackedPosition.z(packet.position) + FACE_Z[packet.face]
                diggers[PackedPosition.pack(x, y, z)] = Digger(shadow.localPlayer.entityId, nanos)
            }

            else -> Unit
        }
    }

    private fun semantic(event: SemanticEvent) {
        val nanos = event.nanos
        when (event) {
            is SemanticEvent.EntityDeath -> if (event.entityId != shadow.localPlayer.entityId) death(
                event.entityId,
                nanos
            )

            is SemanticEvent.OwnDeath -> {
                if (recorderDeathNanos != Long.MIN_VALUE && nanos - recorderDeathNanos < Nanos.PER_SECOND) return
                recorderDeathNanos = nanos
                death(shadow.localPlayer.entityId, nanos, event.messageJson?.let { ChatText.plain(it) })
            }

            is SemanticEvent.TitleShown -> (event.titleJson ?: event.subtitleJson)?.let { ChatText.plain(it) }
                ?.takeIf { it.isNotBlank() }?.let {
                    events.add(nanos, sampled, IndexEventKind.TITLE, -1, -1, Double.NaN, Double.NaN, Double.NaN, 0f, it)
                }

            is SemanticEvent.ChatReceived -> if (event.plainText.isNotBlank()) {
                events.add(
                    nanos,
                    sampled,
                    IndexEventKind.CHAT,
                    -1,
                    -1,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    0f,
                    event.plainText
                )
                killFeed(event.plainText, nanos)
            }

            is SemanticEvent.ScoreboardLineChanged -> events.add(
                nanos, sampled, IndexEventKind.SCOREBOARD, -1, -1, Double.NaN, Double.NaN, Double.NaN,
                event.value.toFloat(), event.line
            )

            is SemanticEvent.BossBarShown -> events.add(
                nanos, sampled, IndexEventKind.BOSS, event.entityId, -1, Double.NaN, Double.NaN, Double.NaN, 0f,
                ChatText.plain(event.nameJson)
            )

            is SemanticEvent.AchievementGet -> events.add(
                nanos, sampled, IndexEventKind.ACHIEVEMENT, shadow.localPlayer.entityId, -1,
                Double.NaN, Double.NaN, Double.NaN, 0f, ChatText.plain(event.json)
            )

            is SemanticEvent.Respawned -> events.add(
                nanos, sampled, IndexEventKind.RESPAWN, shadow.localPlayer.entityId, -1,
                Double.NaN, Double.NaN, Double.NaN, event.dimension.toFloat(), dimensionName(event.dimension)
            )

            is SemanticEvent.Marker -> events.add(
                nanos, sampled, IndexEventKind.MARKER, -1, -1, Double.NaN, Double.NaN, Double.NaN, 0f,
                event.label.ifBlank { "Marker" }
            )

            else -> Unit
        }
    }

    private fun killFeed(text: String, nanos: Long) {
        if (text.contains(": ")) return
        val words =
            text.split(' ', '	').map { it.trim { c -> !c.isLetterOrDigit() && c != '_' } }.filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 24) return
        val known = ArrayList<Pair<Int, Int>>()
        for ((index, word) in words.withIndex()) {
            val id = idByName[word.lowercase()] ?: continue
            known += index to id
        }
        if (known.isEmpty()) return
        val lower = words.map { it.lowercase() }
        val byIndex = lower.indexOf("by")
        val killedIndex = lower.indexOf("killed")
        var victim: Int
        var killer = -1
        when {
            byIndex > 0 && known.any { it.first < byIndex } && known.any { it.first > byIndex } -> {
                victim = known.first { it.first < byIndex }.second
                killer = known.first { it.first > byIndex }.second
            }

            killedIndex > 0 && known.any { it.first < killedIndex } && known.any { it.first > killedIndex } && byIndex < 0 -> {
                killer = known.first { it.first < killedIndex }.second
                victim = known.first { it.first > killedIndex }.second
            }

            known.size == 1 && known[0].first == 0 && lower.drop(1).any { it in DEATH_WORDS } -> victim =
                known[0].second

            else -> return
        }
        if (victim < 0 || victim == killer) return
        val last = lastDeath[victim]
        if (last != null && nanos - last <= CHAT_DEATH_WINDOW) return
        lastDeath.put(victim, nanos)
        if (killer >= 0) lastAttacker.put(victim, Attack(nanos, killer, victim))
        death(victim, nanos, text)
    }

    private fun spawned(id: Int, shooter: Int) {
        val entity = shadow.entities[id] ?: return
        open.remove(id)?.close(sampled - 1)?.let { closed += it }
        val profile = entity.uuid?.let { shadow.players.profile(it) }
        open.put(
            id,
            TrackBuilder(id, entity.kind, entity.type, entity.uuid, profile?.name, false, shooter, sampled)
        )
        profile?.name?.let { idByName[it.lowercase()] = id }
    }

    private fun shooterOf(packet: SpawnObject, entity: ShadowEntity): Int = when (packet.type) {
        60 -> if (packet.data > 0) packet.data - 1 else nearestPlayer(entity)
        63, 64, 66, 90 -> if (packet.data > 0) packet.data else nearestPlayer(entity)
        else -> nearestPlayer(entity)
    }

    private fun nearestPlayer(entity: ShadowEntity): Int {
        var best = -1
        var bestDistance = 2.5 * 2.5
        val local = shadow.localPlayer
        if (local.hasPosition) {
            val d = distanceSquared(local.x, local.y + EYE_HEIGHT, local.z, entity.x, entity.y, entity.z)
            if (d < bestDistance) {
                bestDistance = d
                best = local.entityId
            }
        }
        for (other in shadow.entities.values()) {
            if (!other.isPlayer || other.dead) continue
            val d = distanceSquared(other.x, other.y + EYE_HEIGHT, other.z, entity.x, entity.y, entity.z)
            if (d < bestDistance) {
                bestDistance = d
                best = other.id
            }
        }
        return best
    }

    private fun nearestProjectileShooter(x: Double, y: Double, z: Double): Int {
        var best = -1
        var bestDistance = EXPLOSION_MATCH_RADIUS * EXPLOSION_MATCH_RADIUS
        open.forEach { id, track ->
            if (track.kind != EntityKind.OBJECT || track.type !in EntityNames.PROJECTILES) return@forEach
            val entity = shadow.entities[id] ?: return@forEach
            val d = distanceSquared(entity.x, entity.y, entity.z, x, y, z)
            if (d < bestDistance) {
                bestDistance = d
                best = track.shooterId
            }
        }
        return best
    }

    private fun hurt(victim: Int, nanos: Long, amount: Float) {
        val attacker = inferAttacker(victim, nanos)
        val position = positionOf(victim)
        events.add(
            nanos, sampled, IndexEventKind.HURT, victim, attacker,
            position?.get(0) ?: Double.NaN, position?.get(1) ?: Double.NaN, position?.get(2) ?: Double.NaN,
            amount, null
        )
        if (attacker >= 0) lastAttacker.put(victim, Attack(nanos, attacker, victim))
    }

    private fun inferAttacker(victim: Int, nanos: Long): Int {
        for (attack in recentAttacks.asReversed()) {
            if (nanos - attack.nanos > ATTACK_WINDOW) break
            if (attack.victim == victim) return attack.attacker
        }
        val position = positionOf(victim)
        if (position != null) {
            for (end in projectileEnds.asReversed()) {
                if (nanos - end.nanos > PROJECTILE_WINDOW) break
                if (end.shooter < 0 || end.shooter == victim) continue
                if (distanceSquared(
                        end.x,
                        end.y,
                        end.z,
                        position[0],
                        position[1],
                        position[2]
                    ) <= HIT_RADIUS * HIT_RADIUS
                ) return end.shooter
            }
            for (blast in explosions.asReversed()) {
                if (nanos - blast.nanos > EXPLOSION_HURT_WINDOW) break
                if (blast.shooter < 0) continue
                val reach = blast.radius * 2.0 + 1.0
                if (distanceSquared(
                        blast.x,
                        blast.y,
                        blast.z,
                        position[0],
                        position[1] + 1.0,
                        position[2]
                    ) <= reach * reach
                ) return blast.shooter
            }
            var best = -1
            var bestScore = 0.0
            val local = shadow.localPlayer
            for (other in shadow.entities.values()) {
                if (!other.isPlayer || other.id == victim || other.dead) continue
                val swing = lastSwing[other.id] ?: continue
                if (nanos - swing > SWING_WINDOW) continue
                val score = facingScore(other.x, other.y, other.z, other.headYawDegrees, other.pitchDegrees, position)
                if (score > bestScore) {
                    bestScore = score
                    best = other.id
                }
            }
            if (local.hasPosition && victim != local.entityId) {
                val swing = lastSwing[local.entityId]
                if (swing != null && nanos - swing <= SWING_WINDOW) {
                    val score = facingScore(local.x, local.y, local.z, local.yaw, local.pitch, position)
                    if (score > bestScore) best = local.entityId
                }
            }
            if (best >= 0) return best
        }
        return -1
    }

    private fun facingScore(x: Double, y: Double, z: Double, yaw: Float, pitch: Float, target: DoubleArray): Double {
        val dx = target[0] - x
        val dy = target[1] + 1.0 - (y + EYE_HEIGHT)
        val dz = target[2] - z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (distance !in 1e-6..REACH) return 0.0
        val yawRadians = Math.toRadians(yaw.toDouble())
        val pitchRadians = Math.toRadians(pitch.toDouble())
        val fx = -sin(yawRadians) * cos(pitchRadians)
        val fy = -sin(pitchRadians)
        val fz = cos(yawRadians) * cos(pitchRadians)
        val dot = (fx * dx + fy * dy + fz * dz) / distance
        if (dot < FACING_MIN) return 0.0
        return dot / (1.0 + distance)
    }

    private fun death(victim: Int, nanos: Long, message: String? = null) {
        lastDeath.put(victim, nanos)
        val attack = lastAttacker[victim]
        val killer =
            if (attack != null && nanos - attack.nanos <= KILL_WINDOW && attack.attacker != victim) attack.attacker else -1
        val position = positionOf(victim)
        val victimTrack = open[victim] ?: localTrack?.takeIf { it.entityId == victim }
        val victimName = victimTrack?.name ?: victimTrack?.let { EntityNames.of(it.kind, it.type, victim) }
        events.add(
            nanos, sampled, IndexEventKind.DEATH, victim, killer,
            position?.get(0) ?: Double.NaN, position?.get(1) ?: Double.NaN, position?.get(2) ?: Double.NaN,
            0f, message ?: victimName
        )
        if (killer >= 0) {
            events.add(
                nanos, sampled, IndexEventKind.KILL, killer, victim,
                position?.get(0) ?: Double.NaN, position?.get(1) ?: Double.NaN, position?.get(2) ?: Double.NaN,
                0f, victimName
            )
            lastAttacker.remove(victim)
        }
    }

    private fun remember(attack: Attack) {
        recentAttacks.addLast(attack)
        if (recentAttacks.size > 64) recentAttacks.removeFirst()
        lastAttacker.put(attack.victim, attack)
    }

    private fun useFlag(packet: EntityMetadata, nanos: Long) {
        val entry = packet.metadata.firstOrNull { it.index == 0 && it.type == MetadataEntry.BYTE } ?: return
        val entity = shadow.entities[packet.entityId]
        val isLocal = packet.entityId == shadow.localPlayer.entityId
        if (entity == null && !isLocal) return
        if (entity != null && !entity.isPlayer) return
        val previous = if (isLocal) shadow.localPlayer.flagsByte() else entity!!.metadataByte(0)
        val now = entry.byteValue()
        val was = previous and EntityTrack.FLAG_USING != 0
        val using = now and EntityTrack.FLAG_USING != 0
        if (was == using) return
        val held = if (isLocal) shadow.localPlayer.heldItem else entity!!.equipment[0]
        events.add(
            nanos, sampled, if (using) IndexEventKind.USE_START else IndexEventKind.USE_END, packet.entityId, -1,
            Double.NaN, Double.NaN, Double.NaN, 0f, held?.takeIf { !it.isEmpty }?.let { Items.label(it.id) }
        )
    }

    private fun blockChange(nanos: Long, x: Int, y: Int, z: Int, state: Int, by: Int) {
        val from = shadow.world.blockState(x, y, z)
        if (from == state) return
        val packed = PackedPosition.pack(x, y, z)
        var actor = by
        if (actor < 0) {
            val digger = diggers.remove(packed)
            if (digger != null && nanos - digger.nanos <= DIG_WINDOW) actor = digger.entityId
        } else diggers.remove(packed)
        blocks.add(nanos, sampled, x, y, z, from, state, actor)
    }

    private fun positionOf(id: Int): DoubleArray? {
        val local = shadow.localPlayer
        if (id == local.entityId) return if (local.hasPosition) doubleArrayOf(local.x, local.y, local.z) else null
        val entity = shadow.entities[id] ?: return null
        return doubleArrayOf(entity.x, entity.y, entity.z)
    }

    private fun distanceSquared(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return dx * dx + dy * dy + dz * dz
    }

    private fun dimensionName(dimension: Int): String = when (dimension) {
        -1 -> "Nether"
        0 -> "Overworld"
        1 -> "End"
        else -> "Dimension $dimension"
    }

    private class Attack(val nanos: Long, val attacker: Int, val victim: Int)

    private class ProjectileEnd(val nanos: Long, val shooter: Int, val x: Double, val y: Double, val z: Double)

    private class Blast(
        val nanos: Long,
        val shooter: Int,
        val x: Double,
        val y: Double,
        val z: Double,
        val radius: Double
    )

    private class Digger(val entityId: Int, val nanos: Long)

    private class TrackBuilder(
        val entityId: Int,
        val kind: EntityKind,
        val type: Int,
        val uuid: java.util.UUID?,
        var name: String?,
        val isRecorder: Boolean,
        val shooterId: Int,
        val firstTick: Int,
    ) {
        private val x = DoubleColumn()
        private val y = DoubleColumn()
        private val z = DoubleColumn()
        private val yaw = FloatColumn()
        private val pitch = FloatColumn()
        private val headYaw = FloatColumn()
        private val health = FloatColumn()
        private val held = ShortColumn()
        private val armor = Array(4) { ShortColumn() }
        private val flags = ByteColumn()
        private val vehicle = IntColumn()

        fun sampleFrom(shadow: ShadowClient): Boolean {
            val entity = shadow.entities[entityId] ?: return false
            if (name == null && entity.uuid != null) name = shadow.players.profile(entity.uuid!!)?.name
            val healthEntry = entity.metadata[HEALTH_INDEX]
            val healthValue =
                if (healthEntry != null && healthEntry.type == MetadataEntry.FLOAT) healthEntry.value as Float else Float.NaN
            push(
                entity.x,
                entity.y,
                entity.z,
                entity.yawDegrees,
                entity.pitchDegrees,
                entity.headYawDegrees,
                healthValue,
                entity.equipment[0]?.id ?: -1,
                entity.equipment[1]?.id ?: -1,
                entity.equipment[2]?.id ?: -1,
                entity.equipment[3]?.id ?: -1,
                entity.equipment[4]?.id ?: -1,
                (entity.metadataByte(0) and 0x3F) or (if (entity.onGround) EntityTrack.FLAG_ON_GROUND else 0),
                entity.vehicleId
            )
            return true
        }

        fun push(
            px: Double, py: Double, pz: Double, pyaw: Float, ppitch: Float, phead: Float, phealth: Float,
            pheld: Int, boots: Int, leggings: Int, chest: Int, helmet: Int, pflags: Int, pvehicle: Int,
        ) {
            x.add(px)
            y.add(py)
            z.add(pz)
            yaw.add(pyaw)
            pitch.add(ppitch)
            headYaw.add(phead)
            health.add(phealth)
            held.add(pheld.toShort())
            armor[0].add(boots.toShort())
            armor[1].add(leggings.toShort())
            armor[2].add(chest.toShort())
            armor[3].add(helmet.toShort())
            flags.add(pflags.toByte())
            vehicle.add(pvehicle)
        }

        fun close(lastTick: Int): EntityTrack? {
            val length = x.size
            if (length == 0) return null
            val last = minOf(lastTick, firstTick + length - 1)
            if (last < firstTick) return null
            return EntityTrack(
                entityId, kind, type, uuid, name, isRecorder, shooterId, firstTick, last,
                x.toArray(), y.toArray(), z.toArray(), yaw.toArray(), pitch.toArray(), headYaw.toArray(),
                health.toArray(), held.toArray(), Array(4) { armor[it].toArray() }, flags.toArray(), vehicle.toArray()
            )
        }
    }

    private class EventsBuilder {
        private val nanos = LongColumn(1024)
        private val tick = IntColumn(1024)
        private val kind = ByteColumn(1024)
        private val a = IntColumn(1024)
        private val b = IntColumn(1024)
        private val x = DoubleColumn(1024)
        private val y = DoubleColumn(1024)
        private val z = DoubleColumn(1024)
        private val value = FloatColumn(1024)
        private val text = ArrayList<String?>(1024)

        fun add(
            pnanos: Long, ptick: Int, pkind: IndexEventKind, pa: Int, pb: Int,
            px: Double, py: Double, pz: Double, pvalue: Float, ptext: String?,
        ) {
            nanos.add(pnanos)
            tick.add(ptick)
            kind.add(pkind.ordinal.toByte())
            a.add(pa)
            b.add(pb)
            x.add(px)
            y.add(py)
            z.add(pz)
            value.add(pvalue)
            text.add(ptext)
        }

        fun build(): EventTable = EventTable(
            nanos.toArray(), tick.toArray(), kind.toArray(), a.toArray(), b.toArray(),
            x.toArray(), y.toArray(), z.toArray(), value.toArray(), text.toTypedArray()
        )
    }

    private class BlocksBuilder {
        private val nanos = LongColumn(1024)
        private val tick = IntColumn(1024)
        private val x = IntColumn(1024)
        private val y = IntColumn(1024)
        private val z = IntColumn(1024)
        private val from = IntColumn(1024)
        private val to = IntColumn(1024)
        private val by = IntColumn(1024)

        fun add(pnanos: Long, ptick: Int, px: Int, py: Int, pz: Int, pfrom: Int, pto: Int, pby: Int) {
            nanos.add(pnanos)
            tick.add(ptick)
            x.add(px)
            y.add(py)
            z.add(pz)
            from.add(pfrom)
            to.add(pto)
            by.add(pby)
        }

        fun build(): BlockChangeTable = BlockChangeTable(
            nanos.toArray(),
            tick.toArray(),
            x.toArray(),
            y.toArray(),
            z.toArray(),
            from.toArray(),
            to.toArray(),
            by.toArray()
        )
    }

    private companion object {
        const val HEALTH_INDEX = 6
        const val ITEM_STACK_INDEX = 10
        const val EYE_HEIGHT = 1.62
        const val REACH = 6.5
        const val FACING_MIN = 0.55
        const val HIT_RADIUS = 2.5
        val ATTACK_WINDOW = Nanos.ofMillis(200)
        val SWING_WINDOW = Nanos.ofMillis(250)
        val PROJECTILE_WINDOW = Nanos.ofMillis(150)
        val EXPLOSION_MATCH_WINDOW = Nanos.ofMillis(100)
        const val EXPLOSION_MATCH_RADIUS = 6.0
        val EXPLOSION_HURT_WINDOW = Nanos.ofMillis(200)
        val KILL_WINDOW = Nanos.ofSeconds(3)
        val DIG_WINDOW = Nanos.ofMillis(400)
        val CHAT_DEATH_WINDOW = Nanos.ofSeconds(2)
        val DEATH_WORDS = setOf(
            "died",
            "fell",
            "burned",
            "burnt",
            "drowned",
            "suffocated",
            "starved",
            "withered",
            "exploded",
            "void",
            "blew",
            "killed",
            "slain",
            "shot",
            "blown",
            "pricked",
            "squashed",
            "impaled",
            "roasted",
            "struck",
            "flames",
            "lava",
            "kinetic",
            "cactus",
            "ground",
        )
        val FACE_X = intArrayOf(0, 0, 0, 0, -1, 1)
        val FACE_Y = intArrayOf(-1, 1, 0, 0, 0, 0)
        val FACE_Z = intArrayOf(0, 0, -1, 1, 0, 0)
    }
}
