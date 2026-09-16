package gg.sona.afterimage.mc263.ui

import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.mc.common.ui.WorkspaceHost
import gg.sona.afterimage.mc.common.ui.WorkspaceScreens
import gg.sona.afterimage.mc263.McGfx
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component

class WorkspaceScreen(private val host: WorkspaceHost) : Screen(Component.literal("Afterimage")) {
    override fun isPauseScreen(): Boolean = false

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) = Unit

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        WorkspaceOverlay.pending = host
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.isEscape) {
            if (!host.consumesEscape) ScreenGuard.allow { minecraft.gui.setScreen(null) }
            return true
        }
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        host.typed(event.codepoint().toChar())
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY != 0.0) host.scroll(if (scrollY > 0) 1f else -1f)
        return true
    }

    override fun removed() {
        host.onScreenClosed()
    }

    override fun shouldCloseOnEsc(): Boolean = false
}

object WorkspaceOverlay {
    @Volatile
    var pending: WorkspaceHost? = null

    fun drawAfterGui() {
        val host = pending ?: return
        pending = null
        host.render()
    }
}

class McWorkspaceScreens(private val minecraft: Minecraft) : WorkspaceScreens {
    override val isWorkspaceOpen: Boolean get() = minecraft.gui.screen() is WorkspaceScreen

    override fun openWorkspace(host: WorkspaceHost) = minecraft.gui.setScreen(WorkspaceScreen(host))

    override fun closeWorkspace() = ScreenGuard.allow { minecraft.gui.setScreen(null) }

    override fun mainTarget(): Target = McGfx.mainTarget(minecraft)
}

object ScreenGuard {
    private var allowed = 0

    val closing: Boolean get() = allowed > 0

    inline fun <T> allow(block: () -> T): T {
        begin()
        try {
            return block()
        } finally {
            end()
        }
    }

    fun begin() {
        allowed++
    }

    fun end() {
        if (allowed > 0) allowed--
    }
}
