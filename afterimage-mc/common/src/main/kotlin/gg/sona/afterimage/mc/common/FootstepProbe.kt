package gg.sona.afterimage.mc.common

fun interface FootstepProbe {
    fun blockAt(x: Int, y: Int, z: Int): FootstepBlock
}