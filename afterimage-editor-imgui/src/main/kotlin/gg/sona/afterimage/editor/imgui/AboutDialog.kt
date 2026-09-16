package gg.sona.afterimage.editor.imgui

import imgui.ImFont
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.type.ImBoolean

class AboutDialog(private val context: EditorContext) {

    private val dialog = Dialog("About ${Brand.NAME}", Icon.INFO, 420f, 400f).also { it.headerless = true }

    fun draw(open: ImBoolean) {
        dialog.draw(open, { content() })
    }

    private fun content() {
        val size = EditorFonts.px(96f)
        val x = ImGui.getCursorScreenPosX() + (ImGui.getContentRegionAvailX() - size) / 2f
        val y = ImGui.getCursorScreenPosY() + EditorFonts.px(4f)
        Brand.icon(context.host, ImGui.getWindowDrawList(), x, y, size, size * 0.225f)
        ImGui.setCursorScreenPos(ImGui.getCursorScreenPosX(), y + size + EditorFonts.px(20f))
        centered(Brand.NAME, EditorFonts.display, EditorTheme.TEXT.u32)
        centered("Version ${context.host.version} for ${context.host.platformLabel}", EditorFonts.small, EditorTheme.TEXT_MUTED.u32)
        ImGui.dummy(0f, EditorFonts.px(10f))
        val style = ImGui.getStyle()
        val bottom = ImGui.getWindowHeight() - style.windowPaddingY - EditorFonts.small.fontSize - style.itemSpacingY
        ImGui.setCursorPosY(maxOf(ImGui.getCursorPosY(), bottom))
        centered(Brand.COPYRIGHT, EditorFonts.small, EditorTheme.TEXT_DIM.u32)
    }

    private fun centered(text: String, font: ImFont, color: Int) {
        EditorFonts.with(font) {
            ImGui.setCursorPosX((ImGui.getWindowWidth() - Widgets.textWidth(text)) / 2f)
            ImGui.pushStyleColor(ImGuiCol.Text, color)
            ImGui.textUnformatted(text)
            ImGui.popStyleColor()
        }
    }
}
