package gg.sona.afterimage.mc

import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.mc.McGfx.asTarget
import gg.sona.afterimage.mc.common.ThumbnailHost
import net.minecraft.client.Minecraft

class McThumbnailHost(private val minecraft: Minecraft) : ThumbnailHost {
    override fun mainTarget(): Target = minecraft.renderTarget.asTarget()
    override fun windowWidth(): Int = minecraft.width
    override fun windowHeight(): Int = minecraft.height
    override fun worldLoaded(): Boolean = minecraft.world != null
    override fun runOnGameThread(action: () -> Unit) = minecraft.executeTask(Runnable { action() }).let { }
}
