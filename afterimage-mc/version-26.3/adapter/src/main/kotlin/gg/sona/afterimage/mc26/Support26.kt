package gg.sona.afterimage.mc26

import gg.sona.afterimage.core.log.LogLevel
import gg.sona.afterimage.core.log.LogSink
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.mc.common.SdlWindow
import gg.sona.afterimage.mc.common.ThumbnailHost
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

object Log4jSink26 : LogSink {
    override fun log(level: LogLevel, logger: String, message: String, error: Throwable?) {
        val target = LoggerFactory.getLogger(logger)
        when (level) {
            LogLevel.DEBUG -> target.debug(message, error)
            LogLevel.INFO -> target.info(message, error)
            LogLevel.WARN -> target.warn(message, error)
            LogLevel.ERROR -> target.error(message, error)
        }
    }
}

class WindowDisplay26(private val minecraft: Minecraft) : SdlWindow.Display {
    override val handle: Long get() = minecraft.window.handle()
    override val isCreated: Boolean get() = minecraft.window.handle() != 0L
    override val windowWidth: Int get() = minecraft.window.screenWidth
    override val windowHeight: Int get() = minecraft.window.screenHeight
    override val framebufferWidth: Int get() = minecraft.window.width
    override val framebufferHeight: Int get() = minecraft.window.height
}

class ThumbnailHost26(private val minecraft: Minecraft) : ThumbnailHost {
    override fun mainTarget(): Target = McGfx26.mainTarget(minecraft)
    override fun windowWidth(): Int = minecraft.window.width
    override fun windowHeight(): Int = minecraft.window.height
    override fun worldLoaded(): Boolean = minecraft.level != null
    override fun runOnGameThread(action: () -> Unit) {
        minecraft.execute { action() }
    }
}
