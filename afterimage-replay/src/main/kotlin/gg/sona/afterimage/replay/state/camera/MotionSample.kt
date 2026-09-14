package gg.sona.afterimage.replay.state.camera

class MotionSample {
    var nanos: Long = Long.MIN_VALUE
    var a: Double = 0.0
    var b: Double = 0.0
    var c: Double = 0.0

    fun set(nanos: Long, a: Double, b: Double, c: Double) {
        this.nanos = nanos
        this.a = a
        this.b = b
        this.c = c
    }
}