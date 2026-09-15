package gg.sona.afterimage.editor.imgui

import imgui.ImGui
import imgui.flag.*
import imgui.type.ImString

class CommandPalette {

    private val query = ImString(160)
    private var selected = 0
    private var focusInput = false
    private var pendingRun: PaletteCommand? = null

    var open: Boolean = false
        private set

    fun show() {
        open = true
        focusInput = true
        selected = 0
        query.set("")
    }

    fun hide() {
        open = false
    }

    fun toggle() = if (open) hide() else show()

    fun draw(frame: FrameContext, commands: List<PaletteCommand>) {
        pendingRun?.let { command ->
            pendingRun = null
            command.run()
        }
        if (!open) return
        val width = minOf(frame.displayWidth - EditorFonts.px(80f), EditorFonts.px(600f))
        val matches = rank(commands, query.get()).take(MAX_RESULTS)
        if (selected >= matches.size) selected = maxOf(0, matches.size - 1)
        val rowHeight = ROW_HEIGHT
        val pad = EditorFonts.px(8f)
        val inputHeight = EditorFonts.px(44f)
        val listHeight = if (matches.isEmpty()) EditorFonts.px(48f) else matches.size * rowHeight
        val height = inputHeight + pad + listHeight + pad + FOOTER_HEIGHT
        ImGui.setNextWindowPos((frame.displayWidth - width) / 2f, frame.displayHeight * 0.16f, ImGuiCond.Always)
        ImGui.setNextWindowSize(width, height, ImGuiCond.Always)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, EditorFonts.px(12f))
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 1f)
        ImGui.pushStyleColor(ImGuiCol.WindowBg, EditorTheme.PANEL_RAISED.u32)
        ImGui.pushStyleColor(ImGuiCol.Border, EditorTheme.BORDER_SOFT.u32(0.16f))
        val flags =
            ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoDocking or ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse
        if (ImGui.begin("##command-palette", flags)) {
            val list = ImGui.getWindowDrawList()
            val x0 = ImGui.getWindowPosX()
            val y0 = ImGui.getWindowPosY()
            input(x0, y0, width, inputHeight, matches)
            list.addLine(x0, y0 + inputHeight, x0 + width, y0 + inputHeight, EditorTheme.SEPARATOR.u32, 1f)
            ImGui.setCursorScreenPos(x0 + pad, y0 + inputHeight + pad)
            if (matches.isEmpty()) {
                EditorFonts.with(EditorFonts.small) {
                    val text = "No matching command"
                    list.addText(
                        x0 + (width - Widgets.textWidth(text)) / 2f,
                        y0 + inputHeight + pad + (EditorFonts.px(48f) - ImGui.getFontSize()) / 2f,
                        EditorTheme.TEXT_DIM.u32,
                        text
                    )
                }
            }
            for ((index, command) in matches.withIndex()) {
                ImGui.setCursorScreenPos(x0 + pad, y0 + inputHeight + pad + index * rowHeight)
                row(list, index, command, width - pad * 2f, rowHeight)
            }
            footer(list, x0, y0 + height - FOOTER_HEIGHT, width)
            if (!ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows) && !ImGui.isWindowAppearing()) hide()
        }
        ImGui.end()
        ImGui.popStyleColor(2)
        ImGui.popStyleVar(3)
    }

    private fun input(x0: Float, y0: Float, width: Float, height: Float, matches: List<PaletteCommand>) {
        val list = ImGui.getWindowDrawList()
        val iconSize = EditorFonts.px(16f)
        val inset = EditorFonts.px(16f)
        Icons.draw(list, Icon.SEARCH, x0 + inset, y0 + (height - iconSize) / 2f, iconSize, EditorTheme.TEXT_MUTED.u32)
        val textX = x0 + inset + iconSize + EditorFonts.px(10f)
        ImGui.setCursorScreenPos(textX, y0 + (height - ImGui.getFrameHeight()) / 2f)
        ImGui.setNextItemWidth(x0 + width - inset - textX)
        if (focusInput) {
            ImGui.setKeyboardFocusHere()
            focusInput = false
        }
        ImGui.pushStyleColor(ImGuiCol.FrameBg, 0)
        ImGui.pushStyleColor(ImGuiCol.FrameBgHovered, 0)
        ImGui.pushStyleColor(ImGuiCol.FrameBgActive, 0)
        EditorFonts.with(EditorFonts.bodyMedium) {
            ImGui.inputTextWithHint("##palette-query", "Type a command", query, ImGuiInputTextFlags.AutoSelectAll)
        }
        ImGui.popStyleColor(3)
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow, true) && matches.isNotEmpty()) selected = (selected + 1) % matches.size
        if (ImGui.isKeyPressed(ImGuiKey.UpArrow, true) && matches.isNotEmpty()) selected = (selected - 1 + matches.size) % matches.size
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) hide()
        if (ImGui.isKeyPressed(ImGuiKey.Enter, false) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter, false)) {
            matches.getOrNull(selected)?.let { activate(it) }
        }
    }

    private fun row(list: imgui.ImDrawList, index: Int, command: PaletteCommand, rowWidth: Float, rowHeight: Float) {
        val rowX = ImGui.getCursorScreenPosX()
        val rowY = ImGui.getCursorScreenPosY()
        val hovered = ImGui.isMouseHoveringRect(rowX, rowY, rowX + rowWidth, rowY + rowHeight)
        if (hovered && (ImGui.getIO().mouseDeltaX != 0f || ImGui.getIO().mouseDeltaY != 0f)) selected = index
        val active = index == selected
        if (active) list.addRectFilled(rowX, rowY, rowX + rowWidth, rowY + rowHeight, EditorTheme.SELECTION_FILL.u32, EditorFonts.px(7f))
        else if (hovered) list.addRectFilled(rowX, rowY, rowX + rowWidth, rowY + rowHeight, EditorTheme.CONTROL_HOVER.u32(0.5f), EditorFonts.px(7f))
        val tint = when {
            !command.enabled -> EditorTheme.TEXT_DIM.u32
            active -> 0xFFFFFFFF.toInt()
            else -> EditorTheme.TEXT.u32
        }
        val subtle = if (active) EditorTheme.TEXT.u32(0.7f) else EditorTheme.TEXT_DIM.u32
        val inset = EditorFonts.px(10f)
        val iconSize = EditorFonts.px(15f)
        Icons.draw(list, command.icon, rowX + inset, rowY + (rowHeight - iconSize) / 2f, iconSize, tint)
        val keys = if (command.shortcut.isEmpty()) emptyList() else KeyCaps.pieces(command.shortcut)
        val capHeight = KeyCaps.fits(rowHeight)
        val keysWidth = KeyCaps.piecesWidth(keys, height = capHeight)
        val right = rowX + rowWidth - inset
        if (keys.isNotEmpty()) {
            KeyCaps.drawPieces(
                list, keys, right - keysWidth, rowY, rowHeight,
                color = if (active) EditorTheme.TEXT.u32 else EditorTheme.TEXT_MUTED.u32, textColor = subtle, capHeight = capHeight
            )
        }
        val groupWidth = EditorFonts.with(EditorFonts.small) {
            val groupX = right - keysWidth - (if (keys.isEmpty()) 0f else EditorFonts.px(14f)) - Widgets.textWidth(command.group)
            list.addText(groupX, rowY + (rowHeight - ImGui.getFontSize()) / 2f, subtle, command.group)
            Widgets.textWidth(command.group)
        }
        val titleX = rowX + inset + iconSize + EditorFonts.px(10f)
        val titleWidth = right - keysWidth - groupWidth - EditorFonts.px(28f) - titleX
        list.addText(titleX, rowY + (rowHeight - ImGui.getFontSize()) / 2f, tint, Widgets.clip(command.title, titleWidth))
        ImGui.dummy(rowWidth, rowHeight)
        if (hovered) {
            Widgets.cursorHand()
            if (ImGui.isMouseClicked(0)) activate(command)
        }
    }

    private fun footer(list: imgui.ImDrawList, x0: Float, top: Float, width: Float) {
        list.addLine(x0, top, x0 + width, top, EditorTheme.SEPARATOR.u32, 1f)
        val parts = listOf("Up Down move", "Enter run", "Esc close")
        var cursor = x0 + EditorFonts.px(14f)
        for ((index, part) in parts.withIndex()) {
            if (index > 0) cursor += EditorFonts.px(16f)
            cursor += KeyCaps.drawPieces(
                list, KeyCaps.pieces(part), cursor, top, FOOTER_HEIGHT,
                color = EditorTheme.TEXT_MUTED.u32, textColor = EditorTheme.TEXT_DIM.u32, capHeight = KeyCaps.fits(FOOTER_HEIGHT)
            )
        }
    }

    private fun activate(command: PaletteCommand) {
        if (!command.enabled) return
        hide()
        pendingRun = command
    }

    private fun rank(commands: List<PaletteCommand>, rawQuery: String): List<PaletteCommand> {
        val query = rawQuery.trim().lowercase()
        if (query.isEmpty()) return commands
        return commands.mapNotNull { command ->
            val score = score(command, query) ?: return@mapNotNull null
            command to score
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun score(command: PaletteCommand, query: String): Int? {
        val haystack = (command.title + " " + command.group).lowercase()
        if (haystack.startsWith(query)) return 1000 - haystack.length
        if (command.title.lowercase().contains(query)) return 700 - command.title.length
        val words = haystack.split(' ')
        if (words.any { it.startsWith(query) }) return 500
        var index = 0
        var gaps = 0
        for (character in query) {
            val found = haystack.indexOf(character, index)
            if (found < 0) return null
            gaps += found - index
            index = found + 1
        }
        return 200 - gaps
    }

    private companion object {
        const val MAX_RESULTS = 12
        val ROW_HEIGHT: Float get() = EditorFonts.px(32f)
        val FOOTER_HEIGHT: Float get() = EditorFonts.px(30f)
    }
}
