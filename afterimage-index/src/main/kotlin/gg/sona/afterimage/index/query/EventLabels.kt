package gg.sona.afterimage.index.query

import gg.sona.afterimage.index.IndexEventKind
import gg.sona.afterimage.index.Items
import gg.sona.afterimage.index.ReplayIndex
import kotlin.math.abs

class EventLabels(private val index: ReplayIndex) {
    fun label(i: Int): String {
        val events = index.events
        val tick = events.tick[i]
        val a = events.a[i]
        val b = events.b[i]
        val text = events.text[i]
        val value = events.value[i]
        return when (events.kindAt(i)) {
            IndexEventKind.KILL -> "${name(a, tick)} killed ${name(b, tick, text)}"
            IndexEventKind.DEATH -> if (b >= 0) "${name(a, tick)} died to ${name(b, tick)}" else "${
                name(
                    a,
                    tick,
                    text
                )
            } died"

            IndexEventKind.HURT -> buildString {
                append(name(a, tick))
                append(" hurt")
                if (b >= 0) append(" by ").append(name(b, tick))
                if (!value.isNaN() && value > 0f) append(String.format(" (%.1f)", value))
            }

            IndexEventKind.ATTACK -> "${name(a, tick)} attacked ${name(b, tick)}"
            IndexEventKind.SWING -> "${name(a, tick)} swung"
            IndexEventKind.CRIT -> "${name(a, tick)} took a ${text ?: "critical"} hit"
            IndexEventKind.USE_START -> "${name(a, tick)} used ${text ?: "an item"}"
            IndexEventKind.USE_END -> "${name(a, tick)} stopped using ${text ?: "an item"}"
            IndexEventKind.EAT -> "${name(a, tick)} ate ${text ?: "food"}"
            IndexEventKind.PICKUP -> "${
                name(
                    a,
                    tick
                )
            } picked up ${if (value > 1f) "${value.toInt()} " else ""}${text ?: "an item"}"

            IndexEventKind.EQUIP -> "${name(a, tick)} equipped ${text ?: "nothing"} (${
                SLOTS[value.toInt().coerceIn(0, 4)]
            })"

            IndexEventKind.EFFECT -> "${name(a, tick)} got ${text}"
            IndexEventKind.PROJECTILE_SPAWN -> if (b >= 0) "${
                name(
                    b,
                    tick
                )
            } shot ${text ?: "a projectile"}" else "${text ?: "projectile"} spawned"

            IndexEventKind.PROJECTILE_END -> if (b >= 0) "${
                name(
                    b,
                    tick
                )
            }'s ${text ?: "projectile"} landed" else "${text ?: "projectile"} landed"

            IndexEventKind.EXPLOSION -> (if (b >= 0) "${
                name(
                    b,
                    tick
                )
            }'s explosion" else "Explosion") + String.format(", radius %.1f", value) + (text?.let { ", $it" } ?: "")

            IndexEventKind.SOUND -> text ?: "sound"
            IndexEventKind.CHAT -> text ?: "chat"
            IndexEventKind.TITLE -> text ?: "title"
            IndexEventKind.BOSS -> "Boss bar: ${text ?: ""}"
            IndexEventKind.SCOREBOARD -> "Scoreboard: ${text ?: ""}"
            IndexEventKind.ACHIEVEMENT -> "Achievement: ${text ?: ""}"
            IndexEventKind.RESPAWN -> "Respawn in ${text ?: "world"}"
            IndexEventKind.DIMENSION -> "Entered ${text ?: "dimension"}"
            IndexEventKind.JOIN -> "${text ?: "Someone"} joined"
            IndexEventKind.LEAVE -> "${text ?: "Someone"} left"
            IndexEventKind.MARKER -> text ?: "Marker"
        }
    }

    fun blockLabel(i: Int): String {
        val blocks = index.blocks
        val to = blocks.to[i] shr 4
        val from = blocks.from[i] shr 4
        val actor = blocks.by[i]
        val who = if (actor >= 0) name(actor, blocks.tick[i]) + " " else ""
        return when {
            to == 0 && from != 0 -> "${who}broke ${Items.label(from)}"
            from == 0 -> "${who}placed ${Items.label(to)}"
            else -> "${who}changed ${Items.label(from)} to ${Items.label(to)}"
        }
    }

    fun name(entityId: Int, tick: Int, fallback: String? = null): String {
        if (entityId < 0) return fallback ?: "someone"
        return index.trackAt(entityId, tick)?.label
            ?: index.tracksOf(entityId).minByOrNull { abs(it.firstTick - tick) }?.label
            ?: fallback ?: "#$entityId"
    }

    private companion object {
        val SLOTS = arrayOf("hand", "boots", "leggings", "chestplate", "helmet")
    }
}
