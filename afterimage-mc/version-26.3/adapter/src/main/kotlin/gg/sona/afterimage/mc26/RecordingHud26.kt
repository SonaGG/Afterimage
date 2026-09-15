package gg.sona.afterimage.mc26

import gg.sona.afterimage.core.time.Nanos
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

class RecordingHud26(private val minecraft: Minecraft) {
    fun render(graphics: GuiGraphicsExtractor, recorder: RecordingController26, enabled: Boolean, workspaceOpen: Boolean) {
        if (!enabled || workspaceOpen || minecraft.gui.hud.isHidden || !recorder.isRecording) return
        if (minecraft.debugEntries.isOverlayVisible) return
        val elapsed = recorder.stats()?.elapsedNanos ?: 0L
        val text = "REC  " + clock(elapsed)
        val font = minecraft.font
        val width = font.width(text)
        val x = 4
        val y = 4
        val height = 13
        graphics.fill(x, y, x + width + 18, y + height, 0x88000000.toInt())
        val blink = (System.nanoTime() / (Nanos.PER_SECOND / 2)) % 2L == 0L
        val dot = if (blink) 0xFFE5484D.toInt() else 0xFF7A2A2C.toInt()
        graphics.fill(x + 4, y + 4, x + 9, y + 9, dot)
        graphics.text(font, text, x + 13, y + 3, 0xFFFFFFFF.toInt(), true)
    }

    private fun clock(nanos: Long): String {
        val totalSeconds = nanos / Nanos.PER_SECOND
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format("%02d:%02d", minutes, seconds)
    }
}
