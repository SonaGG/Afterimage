package gg.sona.afterimage.mc

import net.minecraft.client.gui.screen.Screen

class ExportScreen(private val onCancel: () -> Unit) : Screen() {
    override fun shouldPauseGame(): Boolean = false

    override fun render(mouseX: Int, mouseY: Int, delta: Float) = Unit

    override fun keyPressed(character: Char, key: Int) {
        if (key == KEY_ESCAPE) onCancel()
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int) = Unit

    private companion object {
        const val KEY_ESCAPE = 1
    }
}
