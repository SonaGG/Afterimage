package gg.sona.recast.mc

import gg.sona.recast.core.time.Nanos
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiElement
import net.minecraft.client.render.platform.GlStateManager

class RecordingHud(private val minecraft: Minecraft) {

    fun render(recorder: RecordingController, enabled: Boolean, workspaceOpen: Boolean) {
        if (!enabled || workspaceOpen || minecraft.options.hideGui || !recorder.isRecording) return
        if (minecraft.options.debugEnabled) return
        val elapsed = recorder.stats()?.elapsedNanos ?: 0L
        val text = "REC  " + clock(elapsed)
        val font = minecraft.textRenderer
        val width = font.getWidth(text)
        val x = 4
        val y = 4
        val height = 13
        GuiElement.fill(x, y, x + width + 18, y + height, 0x88000000.toInt())
        val blink = (System.nanoTime() / (Nanos.PER_SECOND / 2)) % 2L == 0L
        val dot = if (blink) 0xFFE5484D.toInt() else 0xFF7A2A2C.toInt()
        GuiElement.fill(x + 4, y + 4, x + 9, y + 9, dot)
        font.drawWithShadow(text, (x + 13).toFloat(), (y + 3).toFloat(), 0xFFFFFF)
        GlStateManager.color4f(1f, 1f, 1f, 1f)
    }

    private fun clock(nanos: Long): String {
        val totalSeconds = nanos / Nanos.PER_SECOND
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format(
            "%02d:%02d",
            minutes,
            seconds
        )
    }
}
