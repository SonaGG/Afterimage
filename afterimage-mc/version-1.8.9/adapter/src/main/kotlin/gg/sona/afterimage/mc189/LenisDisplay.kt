package gg.sona.afterimage.mc189

import gg.sona.afterimage.mc.common.SdlWindow
import pl.tomgirl.lenis.window.DisplaySdl

object LenisDisplay : SdlWindow.Display {
    private val display = DisplaySdl.instance()

    override val handle: Long get() = display.handle
    override val isCreated: Boolean get() = display.isCreated
    override val windowWidth: Int get() = display.windowWidth
    override val windowHeight: Int get() = display.windowHeight
    override val framebufferWidth: Int get() = display.width
    override val framebufferHeight: Int get() = display.height
}
