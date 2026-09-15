package gg.sona.afterimage.index

import gg.sona.afterimage.core.collect.IntObjectMap
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.flashback.ChatText
import gg.sona.afterimage.flashback.EventDeriver
import gg.sona.afterimage.flashback.SemanticEvent
import gg.sona.afterimage.flashback.SemanticEventListener
import gg.sona.afterimage.net.PackedPosition
import gg.sona.afterimage.replay.protocol.ReplayProtocols
import gg.sona.afterimage.replay.source.ReplaySource
import gg.sona.afterimage.world.EntityKind
import gg.sona.afterimage.world.EntityState
import gg.sona.afterimage.world.GameNames
import gg.sona.afterimage.world.RecorderIdentity
import gg.sona.afterimage.world.WorldEvent
import gg.sona.afterimage.world.WorldEventSink
import gg.sona.afterimage.world.WorldState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class IndexBuilder(
    private val source: ReplaySource,
    identity: RecorderIdentity,
    private val tickNanos: Long = Nanos.PER_TICK,
) : WorldEventSink {
    @Volatile
    var progress: Double = 0.0
        private set

    private val cancelled = AtomicBoolean(false)
    private val protocol = ReplayProtocols.forVersion(source.header.protocolVersion)
    private val tracker = protocol.createState(identity)
    private val world: WorldState = tracker.world
    private val names: GameNames = GameNames.forProtocol(source.header.protocolVersion)
    private val deriver = EventDeriver(world)

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
                tracker.apply(packet, this)
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
            source.header.protocolVersion,
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
        dimensions.add(world.dimension)
        val local = world.localPlayer
        if (local.hasPosition) {
            val track = localTrack?.takeIf { it.entityId == local.entityId } ?: TrackBuilder(
                names,
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
                local.held?.id ?: -1,
                local.equipment(1)?.id ?: -1,
                local.equipment(2)?.id ?: -1,
                local.equipment(3)?.id ?: -1,
                local.equipment(4)?.id ?: -1,
                (local.flags and 0x3F) or (if (local.onGround) EntityTrack.FLAG_ON_GROUND else 0),
                local.vehicleId
            )
        } else {
            localTrack?.close(tick - 1)?.let { closed += it }
            localTrack = null
        }
        var stale: ArrayList<Int>? = null
        open.forEach { id, track ->
            if (!track.sampleFrom(world)) (stale ?: ArrayList<Int>().also { stale = it }).add(id)
        }
        stale?.forEach { id -> open.remove(id)?.close(tick - 1)?.let { closed += it } }
        sampled++
    }

    override fun onEvent(event: WorldEvent) {
        val nanos = event.nanos
        val local = world.localPlayer
        when (event) {
            is WorldEvent.BlockChanged -> blockChange(nanos, event.x, event.y, event.z, event.state, event.byEntity)
            is WorldEvent.UseItem -> events.add(
                nanos, sampled, if (event.using) IndexEventKind.USE_START else IndexEventKind.USE_END, event.entityId, -1,
                Double.NaN, Double.NaN, Double.NaN, 0f, event.item?.label
            )

            is WorldEvent.Equipped -> events.add(
                nanos, sampled, IndexEventKind.EQUIP, event.entityId, -1,
                Double.NaN, Double.NaN, Double.NaN, event.slot.toFloat(), event.item?.label
            )

            is WorldEvent.EntityRemoved -> {
                val id = event.entityId
                val entity = world.entities[id]
                if (entity != null && entity.isProjectile) {
                    val shooter = open[id]?.shooterId ?: -1
                    events.add(
                        nanos, sampled, IndexEventKind.PROJECTILE_END, id, shooter,
                        entity.x, entity.y, entity.z, 0f, names.entityName(entity.kind, entity.type)
                    )
                    projectileEnds.addLast(ProjectileEnd(nanos, shooter, entity.x, entity.y, entity.z))
                    if (projectileEnds.size > 64) projectileEnds.removeFirst()
                }
                open.remove(id)?.close(sampled - 1)?.let { closed += it }
            }

            is WorldEvent.WorldJoined -> {
                lastAttacker.clear()
                lastHealth = Float.NaN
                open.forEach { _, track -> track.close(sampled - 1)?.let { closed += it } }
                open.clear()
            }

            is WorldEvent.DimensionChanged -> {
                open.forEach { _, track -> track.close(sampled - 1)?.let { closed += it } }
                open.clear()
                events.add(
                    nanos, sampled, IndexEventKind.DIMENSION, local.entityId, -1,
                    Double.NaN, Double.NaN, Double.NaN, event.dimension.toFloat(), world.dimensionName(event.dimension)
                )
            }

            is WorldEvent.EntitySpawned -> {
                val entity = world.entities[event.entityId] ?: return
                val shooter = if (entity.isProjectile) (if (event.shooterHint >= 0) event.shooterHint else nearestPlayer(entity)) else -1
                spawned(event.entityId, shooter)
                if (entity.isProjectile) events.add(
                    nanos, sampled, IndexEventKind.PROJECTILE_SPAWN, event.entityId, shooter,
                    entity.x, entity.y, entity.z, 0f, names.entityName(entity.kind, entity.type)
                )
            }

            is WorldEvent.ArmSwing -> {
                lastSwing.put(event.entityId, nanos)
                events.add(nanos, sampled, IndexEventKind.SWING, event.entityId, -1, Double.NaN, Double.NaN, Double.NaN, 0f, null)
            }

            is WorldEvent.Critical -> events.add(
                nanos, sampled, IndexEventKind.CRIT, event.entityId, lastAttacker[event.entityId]?.attacker ?: -1,
                Double.NaN, Double.NaN, Double.NaN, 0f, if (event.magic) "sharpness" else null
            )

            is WorldEvent.Eating -> events.add(
                nanos, sampled, IndexEventKind.EAT, event.entityId, -1, Double.NaN, Double.NaN, Double.NaN, 0f,
                world.entities[event.entityId]?.equipment(0)?.label
            )

            is WorldEvent.Hurt -> hurt(event.entityId, nanos, Float.NaN)
            is WorldEvent.LocalHealth -> {
                val previous = lastHealth
                lastHealth = event.health
                if (!previous.isNaN() && event.health < previous && event.health > 0f) hurt(local.entityId, nanos, previous - event.health)
            }

            is WorldEvent.Attack -> {
                events.add(nanos, sampled, IndexEventKind.ATTACK, event.attackerId, event.targetId, Double.NaN, Double.NaN, Double.NaN, 0f, null)
                remember(Attack(nanos, event.attackerId, event.targetId))
            }

            is WorldEvent.ItemCollected -> {
                val item = world.entities[event.collectedId]
                events.add(
                    nanos, sampled, IndexEventKind.PICKUP, event.collectorId, event.collectedId,
                    item?.x ?: Double.NaN, item?.y ?: Double.NaN, item?.z ?: Double.NaN,
                    (event.stack?.count ?: 0).toFloat(),
                    event.stack?.label ?: item?.let { names.entityLabel(it.kind, it.type, it.id) }
                )
            }

            is WorldEvent.EffectApplied -> events.add(
                nanos, sampled, IndexEventKind.EFFECT, event.entityId, -1, Double.NaN, Double.NaN, Double.NaN,
                event.effectId.toFloat(), event.label
            )

            is WorldEvent.Explosion -> {
                val x = event.x
                val y = event.y
                val z = event.z
                var shooter = nearestProjectileShooter(x, y, z)
                if (shooter < 0) for (end in projectileEnds.asReversed()) {
                    if (nanos - end.nanos > EXPLOSION_MATCH_WINDOW) break
                    if (distanceSquared(end.x, end.y, end.z, x, y, z) <= EXPLOSION_MATCH_RADIUS * EXPLOSION_MATCH_RADIUS) {
                        shooter = end.shooter
                        break
                    }
                }
                events.add(
                    nanos, sampled, IndexEventKind.EXPLOSION, -1, shooter, x, y, z, event.radius,
                    if (event.blocks == 0) null else "${event.blocks} blocks"
                )
                explosions.addLast(Blast(nanos, shooter, x, y, z, event.radius.toDouble()))
                if (explosions.size > 32) explosions.removeFirst()
            }

            is WorldEvent.Sound -> events.add(
                nanos, sampled, IndexEventKind.SOUND, -1, -1, event.x, event.y, event.z, event.volume, event.name
            )

            is WorldEvent.PlayerJoined -> events.add(
                nanos, sampled, IndexEventKind.JOIN, -1, -1, Double.NaN, Double.NaN, Double.NaN, 0f, event.name
            )

            is WorldEvent.PlayerLeft -> events.add(
                nanos, sampled, IndexEventKind.LEAVE, -1, -1, Double.NaN, Double.NaN, Double.NaN, 0f, event.name
            )

            is WorldEvent.Digging -> diggers[PackedPosition.pack(event.x, event.y, event.z)] = Digger(event.entityId, nanos)
            is WorldEvent.Placing -> diggers[PackedPosition.pack(event.x, event.y, event.z)] = Digger(event.entityId, nanos)
            else -> Unit
        }
        deriver.onEvent(event)
    }

    private fun semantic(event: SemanticEvent) {
        val nanos = event.nanos
        when (event) {
            is SemanticEvent.EntityDeath -> if (event.entityId != world.localPlayer.entityId) death(
                event.entityId,
                nanos
            )

            is SemanticEvent.OwnDeath -> {
                if (recorderDeathNanos != Long.MIN_VALUE && nanos - recorderDeathNanos < Nanos.PER_SECOND) return
                recorderDeathNanos = nanos
                death(world.localPlayer.entityId, nanos, event.messageJson?.let { ChatText.plain(it) })
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
                nanos, sampled, IndexEventKind.ACHIEVEMENT, world.localPlayer.entityId, -1,
                Double.NaN, Double.NaN, Double.NaN, 0f, ChatText.plain(event.json)
            )

            is SemanticEvent.Respawned -> events.add(
                nanos, sampled, IndexEventKind.RESPAWN, world.localPlayer.entityId, -1,
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
        val entity = world.entities[id] ?: return
        open.remove(id)?.close(sampled - 1)?.let { closed += it }
        val profile = entity.uuid?.let { world.players.profile(it) }
        open.put(
            id,
            TrackBuilder(names, id, entity.kind, entity.type, entity.uuid, profile?.name, false, shooter, sampled)
        )
        profile?.name?.let { idByName[it.lowercase()] = id }
    }

    private fun nearestPlayer(entity: EntityState): Int {
        var best = -1
        var bestDistance = 2.5 * 2.5
        val local = world.localPlayer
        if (local.hasPosition) {
            val d = distanceSquared(local.x, local.y + EYE_HEIGHT, local.z, entity.x, entity.y, entity.z)
            if (d < bestDistance) {
                bestDistance = d
                best = local.entityId
            }
        }
        for (other in world.entities.values()) {
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
            if (!names.isProjectile(track.kind, track.type)) return@forEach
            val entity = world.entities[id] ?: return@forEach
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
            val local = world.localPlayer
            for (other in world.entities.values()) {
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
        val victimName = victimTrack?.name ?: victimTrack?.let { names.entityLabel(it.kind, it.type, victim) }
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

    private fun blockChange(nanos: Long, x: Int, y: Int, z: Int, state: Int, by: Int) {
        val from = world.blockState(x, y, z)
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
        val local = world.localPlayer
        if (id == local.entityId) return if (local.hasPosition) doubleArrayOf(local.x, local.y, local.z) else null
        val entity = world.entities[id] ?: return null
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
        val names: GameNames,
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

        fun sampleFrom(world: WorldState): Boolean {
            val entity = world.entities[entityId] ?: return false
            if (name == null && entity.uuid != null) name = world.players.profile(entity.uuid!!)?.name
            val healthValue = entity.health
            push(
                entity.x,
                entity.y,
                entity.z,
                entity.yawDegrees,
                entity.pitchDegrees,
                entity.headYawDegrees,
                healthValue,
                entity.equipment(0)?.id ?: -1,
                entity.equipment(1)?.id ?: -1,
                entity.equipment(2)?.id ?: -1,
                entity.equipment(3)?.id ?: -1,
                entity.equipment(4)?.id ?: -1,
                (entity.flags and 0x3F) or (if (entity.onGround) EntityTrack.FLAG_ON_GROUND else 0),
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
                names, entityId, kind, type, uuid, name, isRecorder, shooterId, firstTick, last,
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
    }
}
