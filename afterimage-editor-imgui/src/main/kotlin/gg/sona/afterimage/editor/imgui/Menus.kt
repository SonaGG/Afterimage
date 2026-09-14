package gg.sona.afterimage.editor.imgui

import imgui.ImGui
import kotlin.math.ceil
import kotlin.math.floor

object Menus {

    fun item(label: String, shortcut: String = "", selected: Boolean = false, enabled: Boolean = true): Boolean {
        if (shortcut.isEmpty()) return ImGui.menuItem(label, "", selected, enabled)
        val padY = 0f
        val pieces = KeyCaps.pieces(shortcut)
        val width = KeyCaps.piecesWidth(pieces, padY = padY)
        val pressed = ImGui.menuItem(label, spacer(width), false, enabled)
        val list = ImGui.getWindowDrawList()
        val fontSize = ImGui.getFontSize().toFloat()
        val spacing = ImGui.getStyle().itemSpacingX
        val top = ImGui.getItemRectMinY()
        val height = ImGui.getItemRectMaxY() - top
        val right = ImGui.getItemRectMaxX() - (spacing - floor(spacing * 0.5f))
        val color = if (enabled) EditorTheme.TEXT_MUTED.u32 else EditorTheme.TEXT_DIM.u32
        var x = right - width
        if (selected) {
            val check = fontSize * 0.866f
            Icons.draw(list, Icon.CHECK, x - spacing - check, top + (height - check) / 2f, check, EditorTheme.TEXT.u32)
        }
        for ((index, piece) in pieces.withIndex()) {
            if (index > 0) x += KeyCaps.GAP
            if (piece.key) {
                x += KeyCaps.draw(list, x, top + (height - KeyCaps.height(padY = padY)) / 2f, piece.text, color = color, padY = padY)
            } else {
                list.addText(x, top + (height - fontSize) / 2f, color, piece.text)
                x += Widgets.textWidth(piece.text)
            }
        }
        return pressed
    }

    private fun spacer(width: Float): String {
        val space = Widgets.textWidth(" ")
        if (space <= 0f) return " "
        return " ".repeat(ceil(width / space).toInt().coerceAtLeast(1))
    }
}
