package gg.sona.afterimage.editor.imgui

import imgui.ImGui
import imgui.flag.*
import imgui.type.ImString

// TODO: make this actually look nice
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
        val width = minOf(frame.displayWidth - EditorFonts.px(80f), EditorFonts.px(560f))
        val matches = rank(commands, query.get()).take(MAX_RESULTS)
        if (selected >= matches.size) selected = maxOf(0, matches.size - 1)
        val rowHeight = EditorFonts.px(30f)
        val height = EditorFonts.px(56f) + matches.size * rowHeight + EditorFonts.px(14f)
        ImGui.setNextWindowPos((frame.displayWidth - width) / 2f, frame.displayHeight * 0.18f, ImGuiCond.Always)
        ImGui.setNextWindowSize(width, height, ImGuiCond.Always)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, EditorFonts.px(12f))
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, EditorFonts.px(10f), EditorFonts.px(10f))
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 1f)
        ImGui.pushStyleColor(ImGuiCol.WindowBg, EditorTheme.PANEL_RAISED.u32)
        ImGui.pushStyleColor(ImGuiCol.Border, EditorTheme.BORDER_SOFT.u32(0.16f))
        val flags =
            ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoDocking or ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoScrollbar
        if (ImGui.begin("##command-palette", flags)) {
            val list = ImGui.getWindowDrawList()
            val iconSize = ImGui.getFontSize().toFloat()
            val x = ImGui.getCursorScreenPosX()
            val y = ImGui.getCursorScreenPosY()
            Icons.draw(
                list,
                Icon.SEARCH,
                x + EditorFonts.px(4f),
                y + (ImGui.getFrameHeight() - iconSize) / 2f,
                iconSize,
                EditorTheme.TEXT_MUTED.u32
            )
            ImGui.setCursorPosX(ImGui.getCursorPosX() + iconSize + EditorFonts.px(12f))
            ImGui.setNextItemWidth(-1f)
            if (focusInput) {
                ImGui.setKeyboardFocusHere()
                focusInput = false
            }
            ImGui.pushStyleColor(ImGuiCol.FrameBg, 0)
            EditorFonts.with(EditorFonts.bodyMedium) {
                ImGui.inputTextWithHint("##palette-query", "Type a command", query, ImGuiInputTextFlags.AutoSelectAll)
            }
            ImGui.popStyleColor()
            if (ImGui.isKeyPressed(ImGuiKey.DownArrow, true) && matches.isNotEmpty()) selected =
                (selected + 1) % matches.size
            if (ImGui.isKeyPressed(ImGuiKey.UpArrow, true) && matches.isNotEmpty()) selected =
                (selected - 1 + matches.size) % matches.size
            if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) hide()
            if (ImGui.isKeyPressed(ImGuiKey.Enter, false) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter, false)) {
                matches.getOrNull(selected)?.let { activate(it) }
            }
            ImGui.dummy(0f, EditorFonts.px(4f))
            list.addLine(
                x,
                ImGui.getCursorScreenPosY(),
                x + width - EditorFonts.px(20f),
                ImGui.getCursorScreenPosY(),
                EditorTheme.BORDER_SOFT.u32,
                1f
            )
            ImGui.dummy(0f, EditorFonts.px(4f))
            if (matches.isEmpty()) {
                Widgets.smallText("No matching command", EditorTheme.TEXT_DIM.u32)
            }
            for ((index, command) in matches.withIndex()) {
                val rowX = ImGui.getCursorScreenPosX()
                val rowY = ImGui.getCursorScreenPosY()
                val rowWidth = ImGui.getContentRegionAvailX()
                val hovered = ImGui.isMouseHoveringRect(rowX, rowY, rowX + rowWidth, rowY + rowHeight)
                if (hovered && (ImGui.getIO().mouseDeltaX != 0f || ImGui.getIO()
                        .mouseDeltaY != 0f)
                ) selected = index
                val active = index == selected
                if (active) list.addRectFilled(
                    rowX,
                    rowY,
                    rowX + rowWidth,
                    rowY + rowHeight,
                    EditorTheme.SELECTION_FILL.u32,
                    EditorFonts.px(6f)
                )
                else if (hovered) list.addRectFilled(
                    rowX,
                    rowY,
                    rowX + rowWidth,
                    rowY + rowHeight,
                    EditorTheme.CONTROL_HOVER.u32(0.6f),
                    EditorFonts.px(6f)
                )
                val tint = when {
                    !command.enabled -> EditorTheme.TEXT_DIM.u32
                    active -> 0xFFFFFFFF.toInt()
                    else -> EditorTheme.TEXT.u32
                }
                val subtle = if (active) 0xCCFFFFFF.toInt() else EditorTheme.TEXT_DIM.u32
                Icons.draw(
                    list,
                    command.icon,
                    rowX + EditorFonts.px(8f),
                    rowY + (rowHeight - iconSize) / 2f,
                    iconSize,
                    tint
                )
                list.addText(
                    rowX + EditorFonts.px(8f) + iconSize + EditorFonts.px(10f),
                    rowY + (rowHeight - ImGui.getFontSize()) / 2f,
                    tint,
                    command.title
                )
                EditorFonts.with<Unit>(EditorFonts.small) {
                    val smallSize = ImGui.getFontSize()
                    val groupWidth = Widgets.textWidth(command.group)
                    val keys = if (command.shortcut.isEmpty()) emptyList() else KeyCaps.pieces(command.shortcut)
                    val shortcutWidth = KeyCaps.piecesWidth(keys)
                    val right = rowX + rowWidth - EditorFonts.px(10f)
                    val textY = rowY + (rowHeight - smallSize) / 2f
                    var keyX = right - shortcutWidth
                    val keyY = rowY + (rowHeight - KeyCaps.height()) / 2f
                    for ((keyIndex, piece) in keys.withIndex()) {
                        if (keyIndex > 0) keyX += EditorFonts.px(5f)
                        if (piece.key) keyX += KeyCaps.draw(list, keyX, keyY, piece.text, color = subtle)
                        else {
                            list.addText(EditorFonts.small, smallSize, keyX, textY, subtle, piece.text)
                            keyX += Widgets.textWidth(piece.text)
                        }
                    }
                    list.addText(
                        EditorFonts.small,
                        smallSize,
                        right - shortcutWidth - (if (shortcutWidth > 0f) EditorFonts.px(16f) else 0f) - groupWidth,
                        textY,
                        subtle,
                        command.group
                    )
                }
                ImGui.dummy(rowWidth, rowHeight)
                if (hovered && ImGui.isMouseClicked(0)) activate(command)
            }
            if (!ImGui.isWindowFocused(imgui.flag.ImGuiFocusedFlags.RootAndChildWindows) && !ImGui.isWindowAppearing()) hide()
        }
        ImGui.end()
        ImGui.popStyleColor(2)
        ImGui.popStyleVar(3)
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
    }
}
