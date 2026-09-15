package gg.sona.afterimage.editor.imgui

import imgui.ImGui
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiTableFlags

object TimelineTooltip {

    class Row(val label: String, val cells: List<Pair<String, String>>)

    inline fun show(block: () -> Unit) {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, EditorFonts.px(12f), EditorFonts.px(9f))
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, EditorFonts.px(8f))
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, EditorFonts.px(6f), EditorFonts.px(6f))
        ImGui.beginTooltip()
        try {
            block()
        } finally {
            ImGui.endTooltip()
            ImGui.popStyleVar(3)
        }
    }

    fun title(icon: Icon?, color: EditorTheme.Rgb, text: String, detail: String? = null) {
        val list = ImGui.getWindowDrawList()
        val x0 = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val height = EditorFonts.px(18f)
        var x = x0
        if (icon != null) {
            val size = EditorFonts.px(13f)
            Icons.draw(list, icon, x, y + (height - size) / 2f, size, color.u32)
            x += size + EditorFonts.px(7f)
        }
        EditorFonts.with(EditorFonts.bodyMedium) {
            list.addText(x, y + (height - ImGui.getFontSize()) / 2f, EditorTheme.TEXT.u32, text)
            x += Widgets.textWidth(text)
        }
        if (detail != null) EditorFonts.with(EditorFonts.small) {
            x += EditorFonts.px(10f)
            list.addText(x, y + (height - ImGui.getFontSize()) / 2f, EditorTheme.TEXT_MUTED.u32, detail)
            x += Widgets.textWidth(detail)
        }
        ImGui.dummy(x - x0, height)
    }

    fun chips(parts: List<String>) {
        if (parts.isEmpty()) return
        Widgets.chips(parts, EditorTheme.TEXT_MUTED.u32, EditorTheme.CONTROL.u32, EditorFonts.px(20f))
    }

    fun grid(rows: List<Row>) {
        if (rows.isEmpty()) return
        val columns = 1 + rows.maxOf { it.cells.size }
        ImGui.pushStyleVar(ImGuiStyleVar.CellPadding, EditorFonts.px(5f), EditorFonts.px(2f))
        if (ImGui.beginTable("tooltip-grid", columns, ImGuiTableFlags.SizingFixedFit)) {
            EditorFonts.with(EditorFonts.small) {
                for (row in rows) {
                    ImGui.tableNextRow()
                    ImGui.tableSetColumnIndex(0)
                    ImGui.textColored(EditorTheme.TEXT_DIM.r, EditorTheme.TEXT_DIM.g, EditorTheme.TEXT_DIM.b, 1f, row.label)
                    for ((index, cell) in row.cells.withIndex()) {
                        ImGui.tableSetColumnIndex(index + 1)
                        val (name, value) = cell
                        if (name.isNotEmpty()) {
                            ImGui.textColored(EditorTheme.TEXT_DIM.r, EditorTheme.TEXT_DIM.g, EditorTheme.TEXT_DIM.b, 1f, name)
                            ImGui.sameLine(0f, EditorFonts.px(4f))
                        }
                        ImGui.textUnformatted(value)
                    }
                }
            }
            ImGui.endTable()
        }
        ImGui.popStyleVar()
    }

    fun text(line: String) {
        Widgets.smallText(line, EditorTheme.TEXT_MUTED.u32)
    }

    fun hints(parts: List<String>) {
        if (parts.isEmpty()) return
        ImGui.dummy(0f, EditorFonts.px(1f))
        ImGui.separator()
        EditorFonts.with(EditorFonts.small) { Widgets.hintLine(parts, EditorTheme.TEXT_DIM.u32, EditorTheme.TEXT_MUTED.u32) }
    }

    fun simple(icon: Icon?, color: EditorTheme.Rgb, text: String, detail: String? = null, hints: List<String> = emptyList()) = show {
        title(icon, color, text, detail)
        hints(hints)
    }
}
