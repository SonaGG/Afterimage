package gg.sona.recast.editor.pose

import gg.sona.recast.camera.track.interpolator.ValueInterpolator
import kotlin.math.abs

object PoseInterpolator : ValueInterpolator<BodyPose> {
    override fun lerp(a: BodyPose, b: BodyPose, t: Double): BodyPose {
        if (a.parts.isEmpty() && b.parts.isEmpty()) return BodyPose.EMPTY
        val result = LinkedHashMap<BodyPart, PartPose>()
        for (part in BodyPart.entries) {
            val from = a[part]
            val to = b[part]
            if (from == null && to == null) continue
            val fa = from ?: PartPose(to!!.x, to.y, to.z, 0.0)
            val tb = to ?: PartPose(from!!.x, from.y, from.z, 0.0)
            result[part] = PartPose(
                fa.x + shortest(fa.x, tb.x) * t,
                fa.y + shortest(fa.y, tb.y) * t,
                fa.z + shortest(fa.z, tb.z) * t,
                fa.weight + (tb.weight - fa.weight) * t,
            )
        }
        return BodyPose(result)
    }

    override fun distance(a: BodyPose, b: BodyPose): Double {
        var total = 0.0
        for (part in BodyPart.entries) {
            val from = a[part] ?: continue
            val to = b[part] ?: continue
            total += abs(shortest(from.x, to.x)) + abs(shortest(from.y, to.y)) + abs(shortest(from.z, to.z))
        }
        return total
    }

    override fun bezier(p1: BodyPose, control1: BodyPose, control2: BodyPose, p2: BodyPose, t: Double): BodyPose {
        val a = lerp(p1, control1, t)
        val b = lerp(control1, control2, t)
        val c = lerp(control2, p2, t)
        return lerp(lerp(a, b, t), lerp(b, c, t), t)
    }

    override fun tangentControl(previous: BodyPose, current: BodyPose, next: BodyPose, scale: Double): BodyPose {
        val result = LinkedHashMap<BodyPart, PartPose>()
        for ((part, pose) in current.parts) {
            val before = previous[part] ?: pose
            val after = next[part] ?: pose
            result[part] = PartPose(
                pose.x + shortest(before.x, after.x) * scale,
                pose.y + shortest(before.y, after.y) * scale,
                pose.z + shortest(before.z, after.z) * scale,
                pose.weight,
            )
        }
        return BodyPose(result)
    }

    private fun shortest(from: Double, to: Double): Double {
        var delta = (to - from) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return delta
    }
}
