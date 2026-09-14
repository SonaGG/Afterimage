package gg.sona.afterimage.camera.track.interpolator

interface ValueInterpolator<T> {
    fun lerp(a: T, b: T, t: Double): T
    fun distance(a: T, b: T): Double
    fun bezier(p1: T, control1: T, control2: T, p2: T, t: Double): T
    fun tangentControl(previous: T, current: T, next: T, scale: Double): T
    fun translate(value: T, from: T, to: T): T = value
}
