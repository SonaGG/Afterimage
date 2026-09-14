package gg.sona.afterimage.editor.imgui

import imgui.ImDrawList
import imgui.ImGui
import kotlin.math.roundToInt

object Icons {
    fun draw(list: ImDrawList, icon: Icon, x: Float, y: Float, size: Float, color: Int) {
        val pixels = size.roundToInt().coerceAtLeast(1)
        val inset = (size - pixels) / 2f
        list.addText(EditorFonts.icons(size), pixels, x + inset, y + inset, color, icon.glyph)
    }

    fun diamond(
        list: ImDrawList,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int,
        filled: Boolean,
        thickness: Float = 1.5f
    ) {
        if (filled) list.addQuadFilled(cx, cy - radius, cx + radius, cy, cx, cy + radius, cx - radius, cy, color)
        else list.addQuad(cx, cy - radius, cx + radius, cy, cx, cy + radius, cx - radius, cy, color, thickness)
    }

    fun inline(icon: Icon, size: Float = ImGui.getFontSize().toFloat(), color: Int = EditorTheme.TEXT.u32) {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        draw(ImGui.getWindowDrawList(), icon, x, y, size, color)
        ImGui.dummy(size, size)
    }
}
