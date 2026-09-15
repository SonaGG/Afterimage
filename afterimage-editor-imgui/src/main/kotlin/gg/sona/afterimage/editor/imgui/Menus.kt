package gg.sona.afterimage.editor.imgui

import imgui.ImGui
import imgui.flag.ImGuiStyleVar
import kotlin.math.ceil
import kotlin.math.floor

object Menus {

    fun item(label: String, shortcut: String = "", selected: Boolean = false, enabled: Boolean = true): Boolean {
        if (shortcut.isEmpty()) return ImGui.menuItem(label, "", selected, enabled)
        val pieces = KeyCaps.pieces(shortcut)
        val capHeight = KeyCaps.fits(ImGui.getFontSize() + ImGui.getStyle().itemSpacingY)
        val width = KeyCaps.piecesWidth(pieces, height = capHeight)
        val pressed = ImGui.menuItem(label, spacer(width), false, enabled)
        val list = ImGui.getWindowDrawList()
        val fontSize = ImGui.getFontSize().toFloat()
        val spacing = ImGui.getStyle().itemSpacingX
        val top = ImGui.getItemRectMinY()
        val height = ImGui.getItemRectMaxY() - top
        val right = ImGui.getItemRectMaxX() - (spacing - floor(spacing * 0.5f))
        val color = if (enabled) EditorTheme.TEXT_MUTED.u32 else EditorTheme.TEXT_DIM.u32
        val x = right - width
        if (selected) {
            val check = fontSize * 0.866f
            Icons.draw(list, Icon.CHECK, x - spacing - check, top + (height - check) / 2f, check, EditorTheme.TEXT.u32)
        }
        KeyCaps.drawPieces(list, pieces, x, top, height, color = color, textColor = color, capHeight = capHeight)
        return pressed
    }

    fun pushMenuStyle() = ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, EditorFonts.px(6f), EditorFonts.px(8f))

    fun popMenuStyle() = ImGui.popStyleVar()

    private fun spacer(width: Float): String {
        val space = Widgets.textWidth(" ")
        if (space <= 0f) return " "
        return " ".repeat(ceil(width / space).toInt().coerceAtLeast(1))
    }
}
