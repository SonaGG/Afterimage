package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.editor.host.KeybindInfo
import imgui.ImGui
import imgui.flag.ImGuiKey
import imgui.type.ImBoolean
import imgui.type.ImString

class ShortcutsDialog(private val context: EditorContext) {

    class Entry(val keys: String, val action: String, val note: String = "") {
        val alternatives: List<List<KeyCaps.Piece>> = keys.split(" / ").map { KeyCaps.pieces(it) }
        val searchable: String = "$keys $action $note".lowercase()
    }

    class Group(val title: String, val icon: Icon, val entries: List<Entry>)

    val open = ImBoolean(false)
    private val dialog = Dialog("Keyboard Shortcuts", Icon.COMMAND, 840f, 620f)
    private val query = ImString("", 64)
    private var listeningFor: String? = null
    private var focusSearch = false

    init {
        dialog.sections = GROUPS.map { Dialog.Section(it.title, it.icon) } + Dialog.Section(GAME_KEYS, Icon.SETTINGS)
    }

    fun show() {
        open.set(true)
        focusSearch = true
        query.set("")
        listeningFor = null
    }

    fun draw() = dialog.draw(open, { content() })

    private fun content() {
        if (focusSearch) {
            ImGui.setKeyboardFocusHere()
            focusSearch = false
        }
        Widgets.search("##shortcut-search", query, "Search shortcuts")
        ImGui.dummy(0f, EditorFonts.px(6f))
        val text = query.get().trim().lowercase()
        if (text.isNotEmpty()) {
            results(text)
            return
        }
        val section = dialog.section
        if (section >= GROUPS.size) gameKeys()
        else list(GROUPS[section].entries)
    }

    private fun results(text: String) {
        var total = 0
        for (group in GROUPS) {
            val matches = group.entries.filter { text in it.searchable }
            if (matches.isEmpty()) continue
            total += matches.size
            Widgets.header(group.title)
            list(matches)
        }
        val bindings = context.host.keybindings().filter {
            text in it.label.lowercase() || text in it.keyName.lowercase()
        }
        if (bindings.isNotEmpty()) {
            total += bindings.size
            Widgets.header(GAME_KEYS)
            for ((index, binding) in bindings.withIndex()) bindingRow(binding, emptySet(), index < bindings.size - 1)
        }
        if (total == 0) Widgets.emptyState("No shortcuts match", "Try another word, like keyframe or zoom", Icon.SEARCH)
    }

    private fun list(entries: List<Entry>) {
        for ((index, entry) in entries.withIndex()) row(entry, index < entries.size - 1)
    }

    private fun row(entry: Entry, divider: Boolean) {
        val height = if (entry.note.isEmpty()) ROW_HEIGHT else TALL_ROW_HEIGHT
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val width = ImGui.getContentRegionAvailX()
        val list = ImGui.getWindowDrawList()
        val inset = EditorFonts.px(8f)
        val capHeight = KeyCaps.height()
        val gap = EditorFonts.px(8f)
        var keysWidth = 0f
        for ((index, alternative) in entry.alternatives.withIndex()) {
            if (index > 0) keysWidth += OR_WIDTH + gap * 2f
            keysWidth += KeyCaps.piecesWidth(alternative, height = capHeight)
        }
        var cursor = x + width - inset - keysWidth
        for ((index, alternative) in entry.alternatives.withIndex()) {
            if (index > 0) {
                EditorFonts.with(EditorFonts.small) {
                    list.addText(cursor + gap, y + (height - ImGui.getFontSize()) / 2f, EditorTheme.TEXT_DIM.u32, "or")
                }
                cursor += OR_WIDTH + gap * 2f
            }
            cursor += KeyCaps.drawPieces(
                list, alternative, cursor, y, height,
                color = EditorTheme.TEXT.u32, textColor = EditorTheme.TEXT_MUTED.u32, capHeight = capHeight
            )
        }
        val textWidth = width - inset * 2f - keysWidth - EditorFonts.px(16f)
        val bodySize = ImGui.getFontSize().toFloat()
        if (entry.note.isEmpty()) {
            list.addText(x + inset, y + (height - bodySize) / 2f, EditorTheme.TEXT.u32, Widgets.clip(entry.action, textWidth))
        } else {
            val block = bodySize + EditorFonts.small.fontSize + EditorFonts.px(2f)
            val top = y + (height - block) / 2f
            list.addText(x + inset, top, EditorTheme.TEXT.u32, Widgets.clip(entry.action, textWidth))
            EditorFonts.with(EditorFonts.small) {
                list.addText(x + inset, top + bodySize + EditorFonts.px(2f), EditorTheme.TEXT_DIM.u32, Widgets.clip(entry.note, textWidth))
            }
        }
        ImGui.dummy(width, height)
        if (divider) list.addLine(x + inset, y + height, x + width - inset, y + height, EditorTheme.SEPARATOR.u32, 1f)
    }

    private fun gameKeys() {
        val bindings = context.host.keybindings()
        val conflicts = bindings.groupBy { it.keyCode }.filterValues { it.size > 1 }.keys
        if (listeningFor != null && ImGui.isKeyPressed(ImGuiKey.Escape, false)) listeningFor = null
        Widgets.wrappedText("In-game keys. Click a key to rebind it, then press the new key. Escape cancels.", EditorTheme.TEXT_DIM.u32)
        ImGui.dummy(0f, EditorFonts.px(4f))
        for ((index, binding) in bindings.withIndex()) bindingRow(binding, conflicts, index < bindings.size - 1)
        listeningFor?.let { name ->
            context.host.captureNextKeyDown()?.let { code ->
                context.host.setKeybinding(name, code)
                listeningFor = null
            }
        }
    }

    private fun bindingRow(binding: KeybindInfo, conflicts: Set<Int>, divider: Boolean) {
        val (name, label, keyCode, keyName, isDefault) = binding
        val height = ROW_HEIGHT
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val width = ImGui.getContentRegionAvailX()
        val inset = EditorFonts.px(8f)
        val list = ImGui.getWindowDrawList()
        list.addText(x + inset, y + (height - ImGui.getFontSize()) / 2f, EditorTheme.TEXT.u32, label)
        val listening = listeningFor == name
        val conflicted = keyCode in conflicts
        val text = if (listening) "Press a key" else keyName
        val color = when {
            listening -> EditorTheme.SELECTION.u32
            conflicted -> EditorTheme.WARNING.u32
            else -> EditorTheme.TEXT.u32
        }
        val buttonWidth = maxOf(KeyCaps.width(text), EditorFonts.px(44f)) + EditorFonts.px(8f)
        val resetWidth = if (isDefault) 0f else Widgets.buttonWidth("Reset") + EditorFonts.px(6f)
        ImGui.setCursorScreenPos(x + width - inset - buttonWidth - resetWidth, y)
        ImGui.pushID(name)
        try {
            if (KeyCaps.button("bind", text, color, height = height)) listeningFor = if (listening) null else name
            if (conflicted && !listening) Widgets.tooltip("Also bound to another action")
            if (!isDefault) {
                ImGui.sameLine(0f, EditorFonts.px(6f))
                Widgets.centerInRow(y, height, ImGui.getFrameHeight())
                if (Widgets.ghostButton("Reset")) {
                    context.host.resetKeybinding(name)
                    if (listening) listeningFor = null
                }
            }
        } finally {
            ImGui.popID()
        }
        ImGui.setCursorScreenPos(x, y)
        ImGui.dummy(width, height)
        if (divider) list.addLine(x + inset, y + height, x + width - inset, y + height, EditorTheme.SEPARATOR.u32, 1f)
    }

    companion object {
        const val GAME_KEYS = "Game keys"
        private val ROW_HEIGHT: Float get() = EditorFonts.px(32f)
        private val TALL_ROW_HEIGHT: Float get() = EditorFonts.px(44f)
        private val OR_WIDTH: Float get() = EditorFonts.px(12f)

        val GROUPS = listOf(
            Group(
                "Tools", Icon.TOOL_MOVE, listOf(
                    Entry("Q  W  E  R", "View, Move, Rotate and Scale tools"),
                    Entry("X", "Toggle local and world axes"),
                    Entry("Ctrl", "Snap while dragging", "Half blocks, 15 degrees, tenths"),
                    Entry("Click", "Select a keyframe", "Shift adds to the selection, double-click jumps to it"),
                    Entry("Drag", "Box select keyframes", "Drag on an empty part of the scene"),
                    Entry("Double-click", "Insert a keyframe on the path"),
                    Entry("Ctrl+A", "Select all keyframes"),
                    Entry("Delete", "Delete the selection"),
                    Entry("F", "Frame the selection", "The hovered entity, the selected keyframes or the selected entity"),
                    Entry("H", "Toggle the camera path and gizmos"),
                    Entry("Tab", "Fullscreen scene", "Esc leaves fullscreen"),
                )
            ),
            Group(
                "Scene camera", Icon.CAMERA, listOf(
                    Entry("Right drag", "Look around", "WASD flies, Space goes up, Shift goes down"),
                    Entry("Wheel", "Dolly toward the cursor", "While flying it changes the fly speed"),
                    Entry("Alt+Left drag", "Orbit around the point under the cursor"),
                    Entry("Middle drag", "Pan"),
                    Entry("Alt+Right drag", "Dolly in and out"),
                    Entry("Ctrl / Alt", "Fast or slow flight", "Hold while flying"),
                )
            ),
            Group(
                "Playback", Icon.PLAY, listOf(
                    Entry("Space", "Play or pause", "Shift+Space plays from the in point"),
                    Entry("J  K  L", "Shuttle reverse, pause, forward", "Press again to double the speed"),
                    Entry("Left / Right", "Step one frame", "Shift steps one tick"),
                    Entry("Home / End", "Jump to the start or the end"),
                    Entry(", / .", "Previous or next keyframe", "Shift jumps between markers, Alt between events"),
                    Entry("I / O", "Set the in or out point"),
                )
            ),
            Group(
                "Editing", Icon.KEYFRAME, listOf(
                    Entry("Ctrl+K", "Add a camera keyframe from the current view", "Auto key on the toolbar keys every move of a paused camera"),
                    Entry("Ctrl+Shift+K", "Add a keyframe two seconds after the last one"),
                    Entry("Ctrl+Shift+R", "Record your flight as keyframes while playing"),
                    Entry("F9", "Easy ease the selected keyframes", "Shift+F9 eases in, Ctrl+Shift+F9 eases out"),
                    Entry("Ctrl+G", "Graph Editor", "Curves, tangent handles and the speed graph"),
                    Entry("Ctrl+D", "Duplicate the selected keyframe at the playhead"),
                    Entry("Ctrl+C  Ctrl+X  Ctrl+V", "Copy, cut and paste keyframes at the playhead"),
                    Entry("M", "Add a marker", "1 to 8 recolour the selected markers"),
                    Entry("Ctrl+Z / Ctrl+Y", "Undo and redo"),
                    Entry("Ctrl+S", "Save the project"),
                    Entry("Ctrl+E", "Export video"),
                    Entry("F2", "Screenshot"),
                    Entry("Ctrl+P", "Command palette"),
                    Entry("F1", "Keyboard shortcuts"),
                )
            ),
            Group(
                "Timeline", Icon.FILM, listOf(
                    Entry("Wheel", "Zoom around the cursor", "Shift+Wheel pans"),
                    Entry("Drag", "Scrub", "Drag along the ruler"),
                    Entry("Middle drag", "Pan the timeline"),
                    Entry("Alt+drag", "Duplicate a keyframe"),
                    Entry("Shift", "Disable snapping", "Hold while dragging"),
                    Entry("Z / Shift+Z", "Zoom to the in and out range, or fit everything"),
                    Entry("Shift+F", "Follow the playhead"),
                )
            ),
            Group(
                "Graph Editor", Icon.GRAPH, listOf(
                    Entry("Drag", "Move a key in time and value", "Shift locks one axis, Ctrl rounds values"),
                    Entry("Drag", "Adjust a handle", "Horizontal: influence, vertical: slope or speed. Alt edits one side only"),
                    Entry("Drag", "Stretch the selected keyframes in time", "Drag the brackets around the selection"),
                    Entry("Double-click", "Insert a keyframe on that curve"),
                    Entry("Wheel / Shift+wheel", "Zoom time or pan", "Ctrl+wheel scales values when not normalized"),
                    Entry("Middle drag / Alt+drag", "Pan the graph"),
                    Entry("Alt+click", "Solo a channel", "Ctrl+click selects all of its keyframes"),
                    Entry("F", "Fit the selection or everything"),
                )
            ),
        )
    }
}
