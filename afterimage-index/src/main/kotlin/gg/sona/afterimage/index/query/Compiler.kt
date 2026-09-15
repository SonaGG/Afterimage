package gg.sona.afterimage.index.query

import gg.sona.afterimage.index.EntityTrack
import gg.sona.afterimage.index.ReplayIndex
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class Compiler(private val index: ReplayIndex) {

    private val bindings = HashMap<String, EntityBinding>()

    fun entity(name: String, position: Int): EntityBinding = bindings.getOrPut(name.lowercase()) {
        val tracks = index.resolve(name)
        if (tracks.isEmpty()) throw QueryException(unknownEntity(name), position)
        EntityBinding(name, tracks)
    }

    fun compile(expr: Expr): Node = when (expr) {
        is Expr.Num -> constant(Value.Num(expr.value))
        is Expr.Str -> constant(Value.Str(expr.value))
        is Expr.Ref -> ref(expr)
        is Expr.Call -> call(expr)
        is Expr.Compare -> compare(expr)
        is Expr.And -> {
            val left = compile(expr.left)
            val right = compile(expr.right)
            Node { tick -> Value.of(left.eval(tick).truthy && right.eval(tick).truthy) }
        }

        is Expr.Or -> {
            val left = compile(expr.left)
            val right = compile(expr.right)
            Node { tick -> Value.of(left.eval(tick).truthy || right.eval(tick).truthy) }
        }

        is Expr.Not -> {
            val inner = compile(expr.inner)
            Node { tick -> Value.of(!inner.eval(tick).truthy) }
        }
    }

    private fun constant(value: Value): Node = Node { value }

    private fun ref(expr: Expr.Ref): Node {
        val lower = expr.name.lowercase()
        if (lower == "true") return constant(Value.TRUE)
        if (lower == "false") return constant(Value.FALSE)
        val binding = entity(expr.name, expr.position)
        return Node { tick -> if (binding.at(tick) != null) Value.Str(binding.name) else Value.Missing }
    }

    private fun compare(expr: Expr.Compare): Node {
        val left = compile(expr.left)
        val right = compile(expr.right)
        val op = expr.op
        return Node { tick ->
            val a = left.eval(tick)
            val b = right.eval(tick)
            when {
                a is Value.Missing || b is Value.Missing -> Value.FALSE
                a is Value.Num && b is Value.Num -> Value.of(
                    when (op) {
                        "<" -> a.value < b.value
                        "<=" -> a.value <= b.value
                        ">" -> a.value > b.value
                        ">=" -> a.value >= b.value
                        "==" -> a.value == b.value
                        else -> a.value != b.value
                    }
                )

                a is Value.Str && b is Value.Str -> when (op) {
                    "==" -> Value.of(a.value.equals(b.value, true))
                    "!=" -> Value.of(!a.value.equals(b.value, true))
                    else -> Value.FALSE
                }

                a is Value.Bool && b is Value.Bool -> when (op) {
                    "==" -> Value.of(a.value == b.value)
                    "!=" -> Value.of(a.value != b.value)
                    else -> Value.FALSE
                }

                else -> Value.FALSE
            }
        }
    }

    private fun call(expr: Expr.Call): Node {
        val args = expr.args
        fun arity(vararg allowed: Int) {
            if (args.size !in allowed) throw QueryException(
                "${expr.name}() takes ${allowed.joinToString(" or ")} argument${if (allowed.last() == 1) "" else "s"}",
                expr.position
            )
        }

        fun entityArg(position: Int): EntityBinding {
            val arg = args[position]
            val name = when (arg) {
                is Expr.Ref -> arg.name
                is Expr.Str -> arg.value
                else -> throw QueryException("${expr.name}() expects a player name here", arg.position)
            }
            return entity(name, arg.position)
        }

        fun textArg(position: Int): String = when (val arg = args[position]) {
            is Expr.Ref -> arg.name
            is Expr.Str -> arg.value
            is Expr.Num -> arg.value.toInt().toString()
            else -> throw QueryException("${expr.name}() expects an item name here", arg.position)
        }

        fun numberArg(position: Int): Double = when (val arg = args[position]) {
            is Expr.Num -> arg.value
            else -> throw QueryException("${expr.name}() expects a number here", arg.position)
        }

        val tickSeconds = index.tickSeconds
        return when (expr.name) {
            "near", "distance" -> {
                arity(2)
                val a = entityArg(0)
                val b = entityArg(1)
                Node { tick ->
                    val ta = a.at(tick)
                    val tb = b.at(tick)
                    if (ta == null || tb == null) Value.Missing else Value.Num(distance(ta, tick, tb, tick))
                }
            }

            "dist" -> {
                arity(4)
                val a = entityArg(0)
                val x = numberArg(1)
                val y = numberArg(2)
                val z = numberArg(3)
                Node { tick ->
                    val ta = a.at(tick) ?: return@Node Value.Missing
                    val i = ta.index(tick)
                    Value.Num(length(ta.x[i] - x, ta.y[i] - y, ta.z[i] - z))
                }
            }

            "health", "hp" -> {
                arity(1)
                val a = entityArg(0)
                Node { tick ->
                    val ta = a.at(tick) ?: return@Node Value.Missing
                    Value.of(ta.health[ta.index(tick)].toDouble())
                }
            }

            "hearts" -> {
                arity(1)
                val a = entityArg(0)
                Node { tick ->
                    val ta = a.at(tick) ?: return@Node Value.Missing
                    Value.of(ta.health[ta.index(tick)].toDouble() / 2.0)
                }
            }

            "speed" -> {
                arity(1)
                val a = entityArg(0)
                Node { tick ->
                    val ta = a.at(tick) ?: return@Node Value.Missing
                    Value.Num(ta.speedAt(tick, tickSeconds))
                }
            }

            "x", "y", "z" -> {
                arity(1)
                val a = entityArg(0)
                val axis = expr.name
                Node { tick ->
                    val ta = a.at(tick) ?: return@Node Value.Missing
                    val i = ta.index(tick)
                    Value.Num(
                        when (axis) {
                            "x" -> ta.x[i]
                            "y" -> ta.y[i]
                            else -> ta.z[i]
                        }
                    )
                }
            }

            "holding" -> {
                arity(1, 2)
                val a = entityArg(0)
                if (args.size == 1) Node { tick ->
                    val ta = a.at(tick) ?: return@Node Value.Missing
                    val id = ta.held[ta.index(tick)].toInt()
                    if (id < 0) Value.Str("") else Value.Str(index.names.itemName(id) ?: id.toString())
                } else {
                    val item = textArg(1)
                    Node { tick ->
                        val ta = a.at(tick) ?: return@Node Value.FALSE
                        Value.of(index.names.itemMatches(ta.held[ta.index(tick)].toInt(), item))
                    }
                }
            }

            "held" -> {
                arity(1)
                val a = entityArg(0)
                Node { tick ->
                    val ta = a.at(tick) ?: return@Node Value.Missing
                    val id = ta.held[ta.index(tick)].toInt()
                    if (id < 0) Value.Str("") else Value.Str(index.names.itemName(id) ?: id.toString())
                }
            }

            "wearing", "armor" -> {
                arity(2)
                val a = entityArg(0)
                val item = textArg(1)
                Node { tick ->
                    val ta = a.at(tick) ?: return@Node Value.FALSE
                    val i = ta.index(tick)
                    Value.of(ta.armor.any { index.names.itemMatches(it[i].toInt(), item) })
                }
            }

            "sneaking", "sprinting", "using", "blocking", "onground", "burning", "invisible" -> {
                arity(1)
                val a = entityArg(0)
                val mask = when (expr.name) {
                    "sneaking" -> EntityTrack.FLAG_SNEAKING
                    "sprinting" -> EntityTrack.FLAG_SPRINTING
                    "using", "blocking" -> EntityTrack.FLAG_USING
                    "onground" -> EntityTrack.FLAG_ON_GROUND
                    "burning" -> EntityTrack.FLAG_ON_FIRE
                    else -> EntityTrack.FLAG_INVISIBLE
                }
                Node { tick ->
                    val ta = a.at(tick) ?: return@Node Value.FALSE
                    Value.of(ta.flag(tick, mask))
                }
            }

            "airborne", "jumping" -> {
                arity(1)
                val a = entityArg(0)
                Node { tick ->
                    val ta = a.at(tick) ?: return@Node Value.FALSE
                    Value.of(!ta.flag(tick, EntityTrack.FLAG_ON_GROUND))
                }
            }

            "riding" -> {
                arity(1)
                val a = entityArg(0)
                Node { tick ->
                    val ta = a.at(tick) ?: return@Node Value.FALSE
                    Value.of(ta.vehicle[ta.index(tick)] >= 0)
                }
            }

            "present", "alive", "exists" -> {
                arity(1)
                val a = entityArg(0)
                Node { tick -> Value.of(a.at(tick) != null) }
            }

            "looking_at", "lookingat", "facing" -> {
                arity(1, 2)
                val a = entityArg(0)
                if (args.size == 2) {
                    val b = entityArg(1)
                    Node { tick ->
                        val ta = a.at(tick)
                        val tb = b.at(tick)
                        if (ta == null || tb == null) Value.FALSE else Value.of(looksAt(ta, tb, tick))
                    }
                } else Node { tick ->
                    val ta = a.at(tick) ?: return@Node Value.Missing
                    var best: EntityTrack? = null
                    var bestDistance = Double.MAX_VALUE
                    for (other in index.players) {
                        if (other === ta || !other.covers(tick) || other.entityId == ta.entityId) continue
                        if (!looksAt(ta, other, tick)) continue
                        val d = distance(ta, tick, other, tick)
                        if (d < bestDistance) {
                            bestDistance = d
                            best = other
                        }
                    }
                    best?.let { Value.Str(it.label) } ?: Value.Str("")
                }
            }

            "moving_toward", "movingtoward", "approaching" -> {
                arity(2)
                val a = entityArg(0)
                val b = entityArg(1)
                Node { tick ->
                    val ta = a.at(tick)
                    val tb = b.at(tick)
                    if (ta == null || tb == null) return@Node Value.FALSE
                    val i = ta.index(tick)
                    if (i == 0) return@Node Value.FALSE
                    val vx = ta.x[i] - ta.x[i - 1]
                    val vz = ta.z[i] - ta.z[i - 1]
                    val speed = sqrt(vx * vx + vz * vz)
                    if (speed < 0.02) return@Node Value.FALSE
                    val j = tb.index(tick)
                    val dx = tb.x[j] - ta.x[i]
                    val dz = tb.z[j] - ta.z[i]
                    val d = sqrt(dx * dx + dz * dz)
                    if (d < 1e-6) return@Node Value.FALSE
                    Value.of((vx * dx + vz * dz) / (speed * d) > 0.7)
                }
            }

            "count_near", "players_near", "nearby" -> {
                arity(2)
                val a = entityArg(0)
                val radius = numberArg(1)
                Node { tick ->
                    val ta = a.at(tick) ?: return@Node Value.Missing
                    var count = 0
                    for (other in index.players) {
                        if (other.entityId == ta.entityId || !other.covers(tick)) continue
                        if (distance(ta, tick, other, tick) <= radius) count++
                    }
                    Value.Num(count.toDouble())
                }
            }

            "players" -> {
                arity(0)
                Node { tick -> Value.Num(index.players.count { it.covers(tick) }.toDouble()) }
            }

            "dimension" -> {
                arity(0)
                Node { tick -> Value.Num(index.dimensionAt(tick).toDouble()) }
            }

            "time" -> {
                arity(0)
                Node { tick -> Value.Num(tick * tickSeconds) }
            }

            else -> throw QueryException("Unknown function '${expr.name}'", expr.position)
        }
    }

    private fun looksAt(from: EntityTrack, to: EntityTrack, tick: Int): Boolean {
        val i = from.index(tick)
        val j = to.index(tick)
        val ex = from.x[i]
        val ey = from.y[i] + EYE_HEIGHT
        val ez = from.z[i]
        val yaw = Math.toRadians(from.headYaw[i].toDouble())
        val pitch = Math.toRadians(from.pitch[i].toDouble())
        val fx = -sin(yaw) * cos(pitch)
        val fy = -sin(pitch)
        val fz = cos(yaw) * cos(pitch)
        val tx = to.x[j] - ex
        val ty = to.y[j] + 0.9 - ey
        val tz = to.z[j] - ez
        val d = length(tx, ty, tz)
        if (d < 1e-6) return true
        if (d > LOOK_RANGE) return false
        val dot = (fx * tx + fy * ty + fz * tz) / d
        val halfWidth = (HIT_HALF_WIDTH / d).coerceAtMost(0.9)
        return dot >= sqrt(1.0 - halfWidth * halfWidth) - LOOK_SLACK
    }

    private fun distance(a: EntityTrack, ta: Int, b: EntityTrack, tb: Int): Double {
        val i = a.index(ta)
        val j = b.index(tb)
        return length(a.x[i] - b.x[j], a.y[i] - b.y[j], a.z[i] - b.z[j])
    }

    private fun length(x: Double, y: Double, z: Double): Double = sqrt(x * x + y * y + z * z)

    private fun unknownEntity(name: String): String {
        val names = index.playerNames
        val suggestion = names.filter { it.startsWith(name, true) || it.contains(name, true) }.take(3)
        return if (suggestion.isNotEmpty()) "No player named '$name'. Did you mean ${suggestion.joinToString(", ")}?"
        else "No player named '$name' in this recording"
    }

    private companion object {
        const val EYE_HEIGHT = 1.62
        const val LOOK_RANGE = 64.0
        const val HIT_HALF_WIDTH = 0.6
        const val LOOK_SLACK = 0.03
    }
}
