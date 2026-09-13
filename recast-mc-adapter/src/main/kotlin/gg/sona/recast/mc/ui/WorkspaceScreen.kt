package gg.sona.recast.mc.ui

import net.minecraft.client.gui.screen.Screen

class WorkspaceScreen(private val host: WorkspaceHost) : Screen() {
    override fun shouldPauseGame(): Boolean = false

    override fun render(mouseX: Int, mouseY: Int, delta: Float) {
        if (minecraft.world == null) renderBackground()
        host.render()
    }

    override fun keyPressed(character: Char, key: Int) {
        if (key == KEY_ESCAPE) {
            if (!host.consumesEscape) minecraft.openScreen(null)
            return
        }
        host.typed(character)
    }

    override fun handleMouse() {
        super.handleMouse()
        val wheel = org.lwjgl.input.Mouse.getEventDWheel()
        if (wheel != 0) host.scroll(if (wheel > 0) 1f else -1f)
    }

    override fun removed() {
        host.onScreenClosed()
    }

    private companion object {
        const val KEY_ESCAPE = 1
    }
}
