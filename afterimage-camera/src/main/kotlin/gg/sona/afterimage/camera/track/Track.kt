package gg.sona.afterimage.camera.track

import gg.sona.afterimage.camera.track.interpolator.ValueInterpolator
import gg.sona.afterimage.core.spline.CatmullRom

class Track<T>(val interpolator: ValueInterpolator<T>, val name: String = "track") {
    val keyframes: List<Keyframe<T>>
        field = ArrayList<Keyframe<T>>()

    val isEmpty: Boolean get() = keyframes.isEmpty()

    val firstNanos: Long get() = keyframes.firstOrNull()?.timeNanos ?: 0L

    val lastNanos: Long get() = keyframes.lastOrNull()?.timeNanos ?: 0L

    var version: Int = 0
        private set

    var preExtrapolation: Extrapolation = Extrapolation.HOLD
        set(value) {
            if (field == value) return
            field = value
            version++
        }

    var postExtrapolation: Extrapolation = Extrapolation.HOLD
        set(value) {
            if (field == value) return
            field = value
            version++
        }

    fun set(keyframe: Keyframe<T>): Keyframe<T> {
        val index = indexAt(keyframe.timeNanos)
        if (index >= 0) keyframes[index] = keyframe else keyframes.add(-(index + 1), keyframe)
        version++
        return keyframe
    }

    fun remove(timeNanos: Long): Keyframe<T>? {
        val index = indexAt(timeNanos)
        if (index < 0) return null
        val removed = keyframes.removeAt(index)
        version++
        return removed
    }

    fun move(fromNanos: Long, toNanos: Long): Boolean {
        val existing = remove(fromNanos) ?: return false
        set(existing.copy(timeNanos = toNanos))
        return true
    }

    fun clear() {
        if (keyframes.isEmpty()) return
        keyframes.clear()
        version++
    }

    fun update(timeNanos: Long, transform: (Keyframe<T>) -> Keyframe<T>): Boolean {
        val index = indexAt(timeNanos)
        if (index < 0) return false
        keyframes[index] = transform(keyframes[index]).copy(timeNanos = timeNanos)
        version++
        return true
    }

    fun previous(timeNanos: Long): Keyframe<T>? = keyframes.lastOrNull { it.timeNanos < timeNanos }

    fun next(timeNanos: Long): Keyframe<T>? = keyframes.firstOrNull { it.timeNanos > timeNanos }

    fun contains(timeNanos: Long): Boolean =
        keyframes.isNotEmpty() && timeNanos >= keyframes.first().timeNanos && timeNanos <= keyframes.last().timeNanos

    fun covers(timeNanos: Long): Boolean {
        if (keyframes.isEmpty()) return false
        if (contains(timeNanos)) return true
        if (keyframes.size < 2) return false
        return if (timeNanos < firstNanos) preExtrapolation != Extrapolation.HOLD else postExtrapolation != Extrapolation.HOLD
    }

    fun at(timeNanos: Long): Keyframe<T>? = indexAt(timeNanos).let { if (it >= 0) keyframes[it] else null }

    fun segmentAt(timeNanos: Long): Int {
        if (keyframes.size < 2 || timeNanos < firstNanos || timeNanos > lastNanos) return -1
        val index = indexAt(timeNanos)
        if (index >= 0) return minOf(index, keyframes.size - 2)
        return -(index + 1) - 1
    }

    fun valueAt(timeNanos: Long): T? {
        if (keyframes.isEmpty()) return null
        if (keyframes.size == 1) return keyframes.first().value
        val first = keyframes.first().timeNanos
        val last = keyframes.last().timeNanos
        if (timeNanos < first) return extrapolate(timeNanos, preExtrapolation, before = true)
        if (timeNanos > last) return extrapolate(timeNanos, postExtrapolation, before = false)
        return valueInside(timeNanos)
    }

    private fun valueInside(timeNanos: Long): T {
        if (timeNanos <= keyframes.first().timeNanos) return keyframes.first().value
        if (timeNanos >= keyframes.last().timeNanos) return keyframes.last().value
        var index = indexAt(timeNanos)
        if (index >= 0) return keyframes[index].value
        index = -(index + 1) - 1
        val from = keyframes[index]
        val to = keyframes[index + 1]
        val span = to.timeNanos - from.timeNanos
        val raw = (timeNanos - from.timeNanos).toDouble() / span
        return evaluate(index, from.easing.clamped(raw))
    }

    private fun extrapolate(timeNanos: Long, mode: Extrapolation, before: Boolean): T {
        val first = keyframes.first()
        val last = keyframes.last()
        val span = last.timeNanos - first.timeNanos
        if (span <= 0L) return if (before) first.value else last.value
        return when (mode) {
            Extrapolation.HOLD -> if (before) first.value else last.value
            Extrapolation.LOOP -> {
                val offset = Math.floorMod(timeNanos - first.timeNanos, span)
                valueInside(first.timeNanos + offset)
            }

            Extrapolation.PING_PONG -> {
                val cycle = Math.floorMod(timeNanos - first.timeNanos, span * 2)
                valueInside(first.timeNanos + if (cycle <= span) cycle else span * 2 - cycle)
            }

            Extrapolation.LINEAR -> {
                val probe = maxOf(1L, minOf(span / 64L, 50_000_000L))
                if (before) {
                    val inside = valueInside(first.timeNanos + probe)
                    val factor = (first.timeNanos - timeNanos).toDouble() / probe
                    interpolator.lerp(first.value, inside, -factor)
                } else {
                    val inside = valueInside(last.timeNanos - probe)
                    val factor = (timeNanos - last.timeNanos).toDouble() / probe
                    interpolator.lerp(last.value, inside, -factor)
                }
            }
        }
    }

    fun evaluate(index: Int, e: Double): T {
        val from = keyframes[index]
        val to = keyframes[index + 1]
        val t = e
        return when (from.mode) {
            SegmentMode.HOLD -> from.value
            SegmentMode.LINEAR -> interpolator.lerp(from.value, to.value, t)
            SegmentMode.CATMULL_ROM -> {
                val p0 = keyframes.getOrNull(index - 1)?.value ?: interpolator.lerp(to.value, from.value, 2.0)
                val p3 = keyframes.getOrNull(index + 2)?.value ?: interpolator.lerp(from.value, to.value, 2.0)
                val weights = CatmullRom.knotSpacing(
                    interpolator.distance(p0, from.value),
                    interpolator.distance(from.value, to.value),
                    interpolator.distance(to.value, p3),
                )
                CatmullRom.evaluate(p0, from.value, to.value, p3, weights, t, interpolator::lerp)
            }

            SegmentMode.BEZIER -> {
                val control1 = from.handleOut ?: defaultHandleOut(index)
                val control2 = to.handleIn ?: defaultHandleIn(index + 1)
                interpolator.bezier(from.value, control1, control2, to.value, t)
            }
        }
    }

    fun defaultHandleOut(index: Int): T {
        val from = keyframes[index]
        val to = keyframes.getOrNull(index + 1) ?: return from.value
        return interpolator.tangentControl(
            keyframes.getOrNull(index - 1)?.value ?: from.value,
            from.value,
            to.value,
            TANGENT_SCALE
        )
    }

    fun defaultHandleIn(index: Int): T {
        val to = keyframes[index]
        val from = keyframes.getOrNull(index - 1) ?: return to.value
        return interpolator.tangentControl(
            keyframes.getOrNull(index + 1)?.value ?: to.value,
            to.value,
            from.value,
            TANGENT_SCALE
        )
    }

    fun indexOf(timeNanos: Long): Int = indexAt(timeNanos).let { if (it >= 0) it else -1 }

    private fun indexAt(timeNanos: Long): Int {
        var low = 0
        var high = keyframes.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val current = keyframes[middle].timeNanos
            when {
                current < timeNanos -> low = middle + 1
                current > timeNanos -> high = middle - 1
                else -> return middle
            }
        }
        return -(low + 1)
    }

    private companion object {
        const val TANGENT_SCALE = 1.0 / 6.0
    }
}
