package gg.sona.afterimage.mc.common

import gg.sona.afterimage.gfx.Target

interface ThumbnailHost {
    fun mainTarget(): Target?
    fun windowWidth(): Int
    fun windowHeight(): Int
    fun worldLoaded(): Boolean
    fun runOnGameThread(action: () -> Unit)
}
