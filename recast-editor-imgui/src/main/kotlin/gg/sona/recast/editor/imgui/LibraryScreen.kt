package gg.sona.recast.editor.imgui

import gg.sona.recast.clip.export.ExportState
import gg.sona.recast.core.time.Nanos
import gg.sona.recast.editor.ProjectSummary
import gg.sona.recast.editor.Segment
import gg.sona.recast.editor.host.RecordingInfo
import imgui.ImGui
import imgui.flag.*
import imgui.type.ImString
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LibraryScreen(private val context: EditorContext) {

    private class Card(
        val path: Path,
        val name: String,
        val recorded: String,
        val sortKey: Long,
        val size: Long,
        val hasProject: Boolean,
        val info: RecordingInfo?
    ) {
        val title: String get() = info?.title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
        val server: String get() = info?.server?.takeIf { it.isNotBlank() } ?: ""
        val player: String get() = info?.player?.takeIf { it.isNotBlank() } ?: ""
        val subtitle: String
            get() = listOf(server, player.takeIf { it.isNotEmpty() }?.let { "as $it" } ?: "").filter { it.isNotEmpty() }
                .joinToString("    ")
        val duration: String get() = info?.let { TimeFormat.clock(it.durationNanos).substringBefore('.') } ?: ""
    }

    private enum class Sort(val label: String) {
        NEWEST("Newest"), OLDEST("Oldest"), NAME("Name"), LONGEST("Longest"), SIZE("Largest")
    }

    private val filter = ImString("", 64)
    private val renameBuffer = ImString("", 128)
    private var cards: List<Card> = emptyList()
    private val infoCache = HashMap<Path, Pair<Long, RecordingInfo?>>()
    private var lastRefreshNanos = 0L
    private var sort = Sort.NEWEST
    private var confirmDelete: Path? = null
    private var renaming: Path? = null
    private var renameFocus = false
    private var selected: Path? = null
    private var tab = 0
    private var projectSummaries: List<ProjectSummary> = emptyList()
    private var lastProjectsRefreshNanos = 0L
    private var confirmDeleteProject: Path? = null
    private val marked = LinkedHashSet<Path>()
    private var confirmDeleteMany = false
    private var visibleCount = 0
    private var visibleNanos = 0L

    fun draw(frame: FrameContext, bottomInset: Float = 0f) {
        if (frame.nowNanos - lastRefreshNanos > REFRESH_NANOS) {
            lastRefreshNanos = frame.nowNanos
            refresh()
        }
        val viewport = ImGui.getMainViewport()
        ImGui.setNextWindowPos(viewport.workPosX, viewport.workPosY, ImGuiCond.Always)
        ImGui.setNextWindowSize(viewport.workSizeX, viewport.workSizeY - bottomInset, ImGuiCond.Always)
        val flags =
            ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoDocking or ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoBringToFrontOnFocus or ImGuiWindowFlags.NoMove
        ImGui.pushStyleColor(ImGuiCol.WindowBg, EditorTheme.APP_BG.u32)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)
        val open = ImGui.begin("##library", flags)
        ImGui.popStyleVar()
        if (open) {
            sidebar()
            ImGui.sameLine(0f, 0f)
            content(frame)
        }
        ImGui.end()
        ImGui.popStyleColor()
        deleteDialog()
        deleteManyDialog()
        deleteProjectDialog()
    }

    private fun flush(id: String, width: Float, height: Float, flags: Int = 0): Boolean {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)
        val open = ImGui.beginChild(id, width, height, false, flags)
        ImGui.popStyleVar()
        return open
    }

    private fun sidebar() {
        val width = SIDEBAR_WIDTH
        ImGui.pushStyleColor(ImGuiCol.ChildBg, EditorTheme.PANEL_SUNKEN.u32)
        flush("##library-sidebar", width, 0f, ImGuiWindowFlags.NoScrollbar)
        ImGui.popStyleColor()
        val list = ImGui.getWindowDrawList()
        val x = ImGui.getWindowPosX()
        val y = ImGui.getWindowPosY()
        val height = ImGui.getWindowHeight()
        list.addLine(x + width - 1f, y, x + width - 1f, y + height, EditorTheme.SEPARATOR.u32, 1f)
        ImGui.setCursorPos(EditorFonts.px(16f), EditorFonts.px(14f))
        EditorFonts.with(EditorFonts.heading) { ImGui.textUnformatted("Recast") }
        ImGui.setCursorPos(EditorFonts.px(16f), EditorFonts.px(46f))
        EditorFonts.with(EditorFonts.label) {
            ImGui.pushStyleColor(ImGuiCol.Text, EditorTheme.TEXT_DIM.u32)
            ImGui.textUnformatted("LIBRARY")
            ImGui.popStyleColor()
        }
        ImGui.setCursorPosY(EditorFonts.px(64f))
        sidebarItem("Recordings", Icon.FILM, 0, "${cards.size}")
        sidebarItem("Projects", Icon.FOLDER, 1, "${projectSummaries.size}")
        recordingStatus(x, y + height, width)
        ImGui.endChild()
    }

    private fun sidebarItem(label: String, icon: Icon, index: Int, trailing: String) {
        val rowHeight = EditorFonts.px(26f)
        ImGui.setCursorPosX(EditorFonts.px(8f))
        val active = tab == index
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val width = SIDEBAR_WIDTH - EditorFonts.px(16f)
        val pressed = ImGui.invisibleButton("side$index", width, rowHeight)
        val hovered = ImGui.isItemHovered()
        val list = ImGui.getWindowDrawList()
        if (active) list.addRectFilled(x, y, x + width, y + rowHeight, EditorTheme.SELECTION_FILL.u32, EditorFonts.px(5f))
        else if (hovered) list.addRectFilled(x, y, x + width, y + rowHeight, EditorTheme.TEXT.u32(0.05f), EditorFonts.px(5f))
        val iconSize = EditorFonts.px(14f)
        Icons.draw(
            list,
            icon,
            x + EditorFonts.px(10f),
            y + (rowHeight - iconSize) / 2f,
            iconSize,
            if (active) EditorTheme.TEXT.u32 else EditorTheme.TEXT_MUTED.u32
        )
        val font = if (active) EditorFonts.bodyMedium else EditorFonts.body
        list.addText(
            font,
            ImGui.getFontSize(),
            x + EditorFonts.px(32f),
            y + (rowHeight - ImGui.getFontSize()) / 2f,
            EditorTheme.TEXT.u32,
            label
        )
        EditorFonts.with(EditorFonts.small) {
            val trailingWidth = Widgets.textWidth(trailing)
            list.addText(
                x + width - trailingWidth - EditorFonts.px(10f),
                y + (rowHeight - ImGui.getFontSize()) / 2f,
                EditorTheme.TEXT_DIM.u32,
                trailing
            )
        }
        ImGui.setCursorScreenPos(x, y + rowHeight + EditorFonts.px(2f))
        if (pressed) {
            tab = index
            marked.clear()
            selected = null
        }
    }

    private fun recordingStatus(x: Float, bottom: Float, width: Float) {
        val status = context.host.recording.status()
        val inset = EditorFonts.px(12f)
        val list = ImGui.getWindowDrawList()
        list.addLine(x, bottom - STATUS_HEIGHT, x + width - 1f, bottom - STATUS_HEIGHT, EditorTheme.SEPARATOR.u32, 1f)
        ImGui.setCursorScreenPos(x + inset, bottom - STATUS_HEIGHT + EditorFonts.px(12f))
        ImGui.beginGroup()
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, EditorFonts.px(6f), EditorFonts.px(8f))
        when {
            status.recording -> {
                val cy = ImGui.getCursorScreenPosY() + ImGui.getTextLineHeight() / 2f
                list.addCircleFilled(ImGui.getCursorScreenPosX() + EditorFonts.px(5f), cy, EditorFonts.px(4.5f), EditorTheme.RECORD.u32, 16)
                ImGui.dummy(EditorFonts.px(14f), ImGui.getTextLineHeight())
                ImGui.sameLine()
                EditorFonts.with(EditorFonts.smallMedium) {
                    ImGui.textUnformatted("Recording  ${TimeFormat.clock(status.elapsedNanos).substringBefore('.')}")
                }
                if (Widgets.button("Stop recording", width - inset * 2f)) context.host.recording.stop()
            }

            status.connected -> {
                Widgets.smallText("Connected to a world", EditorTheme.TEXT_MUTED.u32)
                if (Widgets.accentButton("Start recording", width - inset * 2f)) context.host.recording.start()
            }

            else -> Widgets.smallText("Join a world to record", EditorTheme.TEXT_DIM.u32)
        }
        ImGui.popStyleVar()
        ImGui.endGroup()
    }

    private fun content(frame: FrameContext) {
        flush("##library-content", 0f, 0f, ImGuiWindowFlags.NoScrollbar)
        val list = ImGui.getWindowDrawList()
        val x = ImGui.getWindowPosX()
        val y = ImGui.getWindowPosY()
        val width = ImGui.getWindowWidth()
        val height = ImGui.getWindowHeight()
        list.addRectFilled(x, y, x + width, y + BAR_HEIGHT, EditorTheme.PANEL.u32)
        list.addLine(x, y + BAR_HEIGHT, x + width, y + BAR_HEIGHT, EditorTheme.SEPARATOR.u32, 1f)
        list.addRectFilled(x, y + height - FOOTER_HEIGHT, x + width, y + height, EditorTheme.PANEL.u32)
        list.addLine(x, y + height - FOOTER_HEIGHT, x + width, y + height - FOOTER_HEIGHT, EditorTheme.SEPARATOR.u32, 1f)
        browserBar(width)
        val padding = EditorFonts.px(16f)
        ImGui.setCursorPos(0f, BAR_HEIGHT + 1f)
        val noticeHeight = notices(padding)
        ImGui.setCursorPos(padding, BAR_HEIGHT + 1f + noticeHeight + EditorFonts.px(12f))
        val bodyHeight = height - BAR_HEIGHT - FOOTER_HEIGHT - noticeHeight - EditorFonts.px(12f) - 1f
        if (tab == 1) projects(width - padding * 2f, bodyHeight) else recordings(width - padding * 2f, bodyHeight)
        footer(x, y + height - FOOTER_HEIGHT, width)
        ImGui.endChild()
    }

    private fun browserBar(width: Float) {
        val inset = EditorFonts.px(12f)
        val control = EditorFonts.px(22f)
        val frame = ImGui.getFrameHeight()
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, EditorFonts.px(8f), 0f)
        ImGui.setCursorPos(inset, (BAR_HEIGHT - control - EditorFonts.px(4f)) / 2f)
        Widgets.segmentedIcons(
            "lib-view",
            listOf(Icon.GRID, Icon.LIST),
            if (context.ui.libraryList) 1 else 0,
            listOf("Filmstrip", "List"),
            control
        )?.let { context.ui.libraryList = it == 1 }
        if (tab == 0) {
            ImGui.sameLine()
            ImGui.setCursorPosY((BAR_HEIGHT - frame) / 2f)
            sortPopup()
        }
        val searchWidth = EditorFonts.px(220f)
        val buttons = EditorFonts.px(24f)
        ImGui.sameLine(width - inset - searchWidth - (buttons + EditorFonts.px(8f)) * 2f)
        ImGui.setCursorPosY((BAR_HEIGHT - frame) / 2f)
        Widgets.search("##library-search", filter, if (tab == 0) "Search recordings" else "Search projects", searchWidth)
        ImGui.sameLine()
        ImGui.setCursorPosY((BAR_HEIGHT - buttons) / 2f)
        if (Widgets.iconButton("lib-refresh", Icon.REFRESH, buttons, "Refresh")) {
            lastRefreshNanos = 0L
            lastProjectsRefreshNanos = 0L
        }
        ImGui.sameLine()
        ImGui.setCursorPosY((BAR_HEIGHT - buttons) / 2f)
        if (Widgets.iconButton("lib-folder", Icon.FOLDER, buttons, "Show in Finder or Explorer")) {
            if (tab == 0) cards.firstOrNull()?.let { openFolder(it.path) } else openFolder(context.host.projectsDirectory)
        }
        ImGui.popStyleVar()
    }

    private fun sortPopup() {
        val label = sort.label
        val height = ImGui.getFrameHeight()
        val width = EditorFonts.with(EditorFonts.small) { Widgets.textWidth(label) } + EditorFonts.px(34f)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val pressed = ImGui.invisibleButton("lib-sort", width, height)
        val hovered = ImGui.isItemHovered()
        val list = ImGui.getWindowDrawList()
        list.addRectFilled(
            x,
            y,
            x + width,
            y + height,
            if (hovered) EditorTheme.CONTROL_HOVER.u32 else EditorTheme.CONTROL.u32,
            EditorFonts.px(5f)
        )
        EditorFonts.with(EditorFonts.small) {
            list.addText(x + EditorFonts.px(9f), y + (height - ImGui.getFontSize()) / 2f, EditorTheme.TEXT.u32, label)
        }
        Icons.draw(
            list,
            Icon.CHEVRON_DOWN,
            x + width - EditorFonts.px(16f),
            y + (height - EditorFonts.px(9f)) / 2f,
            EditorFonts.px(9f),
            EditorTheme.TEXT_DIM.u32
        )
        if (hovered) Widgets.hint("Sort order")
        if (pressed) ImGui.openPopup("lib-sort-menu")
        if (Widgets.beginPopup("lib-sort-menu")) {
            for (option in Sort.entries) if (ImGui.menuItem(option.label, "", sort == option)) sort = option
            Widgets.endPopup()
        }
    }

    private fun notices(padding: Float): Float {
        val start = ImGui.getCursorPosY()
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, EditorFonts.px(8f), EditorFonts.px(4f))
        context.host.replay.lastError()?.let {
            ImGui.setCursorPosX(padding)
            ImGui.dummy(0f, EditorFonts.px(8f))
            ImGui.setCursorPosX(padding)
            Widgets.iconText(Icon.WARNING, it, EditorTheme.WARNING.u32)
        }
        context.exports?.queue()?.handles()
            ?.filter { it.state == ExportState.RUNNING || it.state == ExportState.QUEUED }?.forEach { handle ->
                ImGui.setCursorPosX(padding)
                ImGui.dummy(0f, EditorFonts.px(6f))
                ImGui.setCursorPosX(padding)
                ImGui.alignTextToFramePadding()
                Widgets.smallText(handle.job.name, EditorTheme.TEXT_MUTED.u32)
                ImGui.sameLine()
                Widgets.progress(
                    handle.progress.toFloat(),
                    EditorFonts.px(220f),
                    handle.detail.ifBlank { String.format("%.0f%%", handle.progress * 100) })
                ImGui.sameLine()
                if (Widgets.smallButton("Cancel##${handle.id}")) handle.cancel()
                lastRefreshNanos = minOf(lastRefreshNanos, System.nanoTime() - REFRESH_NANOS + Nanos.ofMillis(500))
            }
        if (context.host.replay.sequenceBuilding()) {
            ImGui.setCursorPosX(padding)
            ImGui.dummy(0f, EditorFonts.px(6f))
            ImGui.setCursorPosX(padding)
            Widgets.pill("Building sequence", EditorTheme.ACCENT, EditorTheme.ACCENT_TEXT.u32)
        }
        ImGui.popStyleVar()
        return ImGui.getCursorPosY() - start
    }

    private fun footer(x: Float, y: Float, width: Float) {
        val inset = EditorFonts.px(12f)
        val list = ImGui.getWindowDrawList()
        val frame = ImGui.getFrameHeight()
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, EditorFonts.px(8f), 0f)
        if (tab == 0 && marked.size >= 2) {
            ImGui.setCursorScreenPos(x + inset, y + (FOOTER_HEIGHT - frame) / 2f)
            ImGui.alignTextToFramePadding()
            EditorFonts.with(EditorFonts.smallMedium) { ImGui.textUnformatted("${marked.size} selected") }
            ImGui.sameLine(0f, EditorFonts.px(14f))
            if (Widgets.accentButton("New project from selection")) newProject(marked.toList())
            Widgets.tooltip("Plays the selected recordings back to back in the order you picked them")
            ImGui.sameLine()
            if (Widgets.dangerButton("Delete")) confirmDeleteMany = true
            ImGui.sameLine()
            if (Widgets.ghostButton("Clear")) marked.clear()
        } else {
            val summary = if (tab == 0) {
                val total = if (visibleNanos > 0L) "    " + TimeFormat.clock(visibleNanos).substringBefore('.') + " total" else ""
                "$visibleCount ${if (visibleCount == 1) "recording" else "recordings"}$total"
            } else "$visibleCount ${if (visibleCount == 1) "project" else "projects"}"
            EditorFonts.with(EditorFonts.small) {
                list.addText(
                    x + inset,
                    y + (FOOTER_HEIGHT - ImGui.getFontSize()) / 2f,
                    EditorTheme.TEXT_MUTED.u32,
                    summary
                )
            }
        }
        if (!context.ui.libraryList) {
            val sliderWidth = EditorFonts.px(110f)
            val icon = EditorFonts.px(12f)
            val right = x + width - inset
            val cy = y + FOOTER_HEIGHT / 2f
            Icons.draw(list, Icon.GRID, right - sliderWidth - icon * 2f - EditorFonts.px(14f), cy - icon / 2f, icon, EditorTheme.TEXT_DIM.u32)
            ImGui.setCursorScreenPos(right - sliderWidth - icon - EditorFonts.px(6f), y + (FOOTER_HEIGHT - frame) / 2f)
            Widgets.slider(
                "##lib-thumb",
                context.ui.libraryThumbSize,
                THUMB_MIN,
                THUMB_MAX,
                width = sliderWidth,
                labelOf = { "" })?.let { context.ui.libraryThumbSize = it }
            if (ImGui.isItemHovered()) Widgets.hint("Clip size")
            Icons.draw(list, Icon.FULLSCREEN, right - icon, cy - icon / 2f, icon, EditorTheme.TEXT_DIM.u32)
        }
        ImGui.popStyleVar()
    }

    private fun recordings(width: Float, height: Float) {
        val query = filter.get().trim().lowercase()
        val visible = cards.filter {
            query.isEmpty() || it.title.lowercase().contains(query) || it.name.lowercase()
                .contains(query) || it.subtitle.lowercase().contains(query)
        }.sortedWith(comparator())
        visibleCount = visible.size
        visibleNanos = visible.sumOf { it.info?.durationNanos ?: 0L }
        flush("##grid", width, height)
        if (visible.isEmpty()) {
            Widgets.emptyState(
                if (cards.isEmpty()) "No recordings yet" else "Nothing matches",
                if (cards.isEmpty()) "Join a world and start recording, or turn on auto record in Settings" else "Try a different search",
                Icon.FILM
            )
        } else if (context.ui.libraryList) {
            recordingsList(visible)
        } else {
            recordingsGrid(visible, width)
        }
        ImGui.endChild()
        if (!ImGui.getIO().wantTextInput && renaming == null) {
            val current = selected
            if (current != null && ImGui.isKeyPressed(ImGuiKey.Enter, false)) open(current)
            if (current != null && ImGui.isKeyPressed(ImGuiKey.Delete, false)) if (marked.size >= 2) confirmDeleteMany =
                true else confirmDelete = current
        }
    }

    private fun recordingsGrid(visible: List<Card>, available: Float) {
        val target = context.ui.libraryThumbSize
        val gap = GAP
        val columns = maxOf(1, ((available + gap) / (target + gap)).toInt())
        val cardWidth = (available - gap * (columns - 1)) / columns
        val cardHeight = cardWidth * 9f / 16f + EditorFonts.px(44f)
        val startX = ImGui.getCursorPosX()
        val startY = ImGui.getCursorPosY()
        for ((index, card) in visible.withIndex()) {
            ImGui.setCursorPos(startX + (index % columns) * (cardWidth + gap), startY + (index / columns) * (cardHeight + gap))
            drawCard(card, cardWidth, cardHeight)
        }
        val rows = (visible.size + columns - 1) / columns
        ImGui.setCursorPos(startX, startY + rows * (cardHeight + gap))
        ImGui.dummy(1f, 1f)
    }

    private fun recordingsList(visible: List<Card>) {
        val flags =
            ImGuiTableFlags.RowBg or ImGuiTableFlags.ScrollY
        ImGui.pushStyleVar(ImGuiStyleVar.CellPadding, EditorFonts.px(8f), EditorFonts.px(4f))
        ImGui.pushStyleColor(ImGuiCol.HeaderHovered, EditorTheme.TEXT.u32(0.05f))
        ImGui.pushStyleColor(ImGuiCol.HeaderActive, EditorTheme.SELECTION_FILL.u32)
        ImGui.pushStyleColor(ImGuiCol.Header, EditorTheme.SELECTION_FILL.u32)
        val open = EditorFonts.with(EditorFonts.small) { ImGui.beginTable("lib-list", 6, flags) }
        if (open) {
            EditorFonts.with(EditorFonts.small) {
                ImGui.tableSetupScrollFreeze(0, 1)
                ImGui.tableSetupColumn("Name", ImGuiTableColumnFlags.WidthStretch, 3f)
                ImGui.tableSetupColumn("Recorded", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(140f))
                ImGui.tableSetupColumn("Duration", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(76f))
                ImGui.tableSetupColumn("Server", ImGuiTableColumnFlags.WidthStretch, 2f)
                ImGui.tableSetupColumn("Player", ImGuiTableColumnFlags.WidthStretch, 1.4f)
                ImGui.tableSetupColumn("Size", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(72f))
                ImGui.pushStyleColor(ImGuiCol.Text, EditorTheme.TEXT_MUTED.u32)
                ImGui.tableHeadersRow()
                ImGui.popStyleColor()
                for (card in visible) listRow(card)
            }
            ImGui.endTable()
        }
        ImGui.popStyleColor(3)
        ImGui.popStyleVar()
    }

    private fun listRow(card: Card) {
        ImGui.pushID(card.path.toString())
        try {
            ImGui.tableNextRow(0, ROW_HEIGHT)
            ImGui.tableSetColumnIndex(0)
            val isSelected = selected == card.path || card.path in marked
            if (renaming == card.path) {
                renameField(card, -1f)
            } else {
                ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, EditorFonts.px(6f), 0f)
                val cx = ImGui.getCursorScreenPosX()
                val cy = ImGui.getCursorScreenPosY()
                ImGui.selectable(
                    "##row",
                    isSelected,
                    ImGuiSelectableFlags.SpanAllColumns or ImGuiSelectableFlags.AllowDoubleClick or ImGuiSelectableFlags.AllowItemOverlap,
                    0f,
                    ROW_HEIGHT
                )
                ImGui.popStyleVar()
                val hovered = ImGui.isItemHovered()
                if (ImGui.isItemClicked(ImGuiMouseButton.Left)) select(card.path)
                if (ImGui.isItemClicked(ImGuiMouseButton.Right)) selected = card.path
                if (hovered && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) open(card.path)
                cardMenu(card)
                if (isSelected && renaming == null && !ImGui.getIO().wantTextInput && ImGui.isKeyPressed(ImGuiKey.F2, false)) startRename(card)
                val list = ImGui.getWindowDrawList()
                val iconSize = EditorFonts.px(12f)
                Icons.draw(
                    list,
                    if (card.hasProject) Icon.KEYFRAME else Icon.FILM,
                    cx,
                    cy + (ROW_HEIGHT - iconSize) / 2f,
                    iconSize,
                    if (card.hasProject) EditorTheme.TEXT.u32 else EditorTheme.TEXT_DIM.u32
                )
                list.addText(
                    cx + iconSize + EditorFonts.px(8f),
                    cy + (ROW_HEIGHT - ImGui.getFontSize()) / 2f,
                    EditorTheme.TEXT.u32,
                    Widgets.clip(card.title, ImGui.getContentRegionAvailX() - iconSize - EditorFonts.px(8f))
                )
            }
            cell(1, card.recorded)
            cell(2, card.duration)
            cell(3, card.server)
            cell(4, card.player)
            cell(5, formatSize(card.size))
        } finally {
            ImGui.popID()
        }
    }

    private fun cell(column: Int, text: String, color: Int = EditorTheme.TEXT_MUTED.u32) {
        ImGui.tableSetColumnIndex(column)
        val list = ImGui.getWindowDrawList()
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        list.addText(
            x,
            y + (ROW_HEIGHT - ImGui.getFontSize()) / 2f,
            color,
            Widgets.clip(text, ImGui.getContentRegionAvailX())
        )
    }

    private fun select(path: Path) {
        selected = path
        if (ImGui.getIO().keyCtrl) {
            if (!marked.remove(path)) marked.add(path)
        } else if (marked.isNotEmpty() && path !in marked) {
            marked.clear()
        }
    }

    private fun cardMenu(card: Card) {
        if (!Widgets.beginContextPopup("card-menu")) return
        if (ImGui.menuItem("Open", "Enter")) open(card.path)
        if (ImGui.menuItem("Rename", "F2")) startRename(card)
        if (ImGui.menuItem("New Project from This")) newProject(listOf(card.path))
        ImGui.separator()
        if (ImGui.menuItem("Show in Folder")) openFolder(card.path)
        if (ImGui.menuItem("Copy Path")) ImGui.setClipboardText(card.path.toAbsolutePath().toString())
        if (ImGui.menuItem("Compact File")) {
            if (context.host.replay.compact(card.path)) context.status("Compacting ${card.title} in the background") else context.status(
                "Close this recording and wait for other jobs before compacting"
            )
        }
        Widgets.tooltip("Rewrites the recording with the current format. Same content, usually 3 to 5 times smaller.")
        ImGui.separator()
        if (ImGui.menuItem("Delete", "Del")) confirmDelete = card.path
        Widgets.endPopup()
    }

    private fun renameField(card: Card, width: Float) {
        ImGui.setNextItemWidth(width)
        if (renameFocus) {
            ImGui.setKeyboardFocusHere()
            renameFocus = false
        }
        if (ImGui.inputText("##rename", renameBuffer, ImGuiInputTextFlags.EnterReturnsTrue or ImGuiInputTextFlags.AutoSelectAll)) commitRename(card)
        if (ImGui.isItemDeactivated() && renaming == card.path) {
            if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) renaming = null else commitRename(card)
        }
    }

    private fun comparator(): Comparator<Card> = when (sort) {
        Sort.NEWEST -> compareByDescending { it.sortKey }
        Sort.OLDEST -> compareBy { it.sortKey }
        Sort.NAME -> compareBy { it.title.lowercase() }
        Sort.LONGEST -> compareByDescending { it.info?.durationNanos ?: 0L }
        Sort.SIZE -> compareByDescending { it.size }
    }

    private fun drawCard(card: Card, cardWidth: Float, cardHeight: Float) {
        ImGui.pushID(card.path.toString())
        try {
            val list = ImGui.getWindowDrawList()
            val x = ImGui.getCursorScreenPosX()
            val y = ImGui.getCursorScreenPosY()
            val isSelected = selected == card.path || card.path in marked
            ImGui.setNextItemAllowOverlap()
            ImGui.invisibleButton("card", cardWidth, cardHeight)
            val hovered = ImGui.isItemHovered()
            if (ImGui.isItemClicked(ImGuiMouseButton.Left)) select(card.path)
            if (ImGui.isItemClicked(ImGuiMouseButton.Right)) selected = card.path
            if (hovered && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) open(card.path)
            cardMenu(card)
            if (isSelected && renaming == null && !ImGui.getIO().wantTextInput && ImGui.isKeyPressed(ImGuiKey.F2, false)) startRename(card)
            val rounding = EditorFonts.px(4f)
            val thumbHeight = cardWidth * 9f / 16f
            val texture = context.host.thumbnail(card.path)
            if (texture != null) {
                list.addImageRounded(
                    texture.toLong(),
                    x,
                    y,
                    x + cardWidth,
                    y + thumbHeight,
                    0f,
                    0f,
                    1f,
                    1f,
                    if (hovered || isSelected) 0xFFFFFFFF.toInt() else 0xFFE4E4E4.toInt(),
                    rounding
                )
            } else {
                list.addRectFilled(x, y, x + cardWidth, y + thumbHeight, EditorTheme.PANEL_RAISED.u32, rounding)
                val iconSize = EditorFonts.px(28f)
                Icons.draw(
                    list,
                    Icon.FILM,
                    x + cardWidth / 2f - iconSize / 2f,
                    y + thumbHeight / 2f - iconSize / 2f,
                    iconSize,
                    EditorTheme.TEXT_DIM.u32
                )
            }
            when {
                isSelected -> list.addRect(x, y, x + cardWidth, y + thumbHeight, EditorTheme.SELECTION.u32, rounding, 0, 2.5f)
                hovered -> list.addRect(x, y, x + cardWidth, y + thumbHeight, EditorTheme.TEXT.u32(0.3f), rounding, 0, 1f)
                else -> list.addRect(x, y, x + cardWidth, y + thumbHeight, EditorTheme.BORDER_SOFT.u32, rounding, 0, 1f)
            }
            if (card.duration.isNotEmpty()) EditorFonts.with(EditorFonts.smallMedium) {
                val label = card.duration
                val labelWidth = Widgets.textWidth(label)
                val pad = EditorFonts.px(5f)
                val right = x + cardWidth - EditorFonts.px(7f)
                val bottom = y + thumbHeight - EditorFonts.px(7f)
                list.addRectFilled(
                    right - labelWidth - pad * 2f,
                    bottom - ImGui.getFontSize() - pad,
                    right,
                    bottom,
                    0xBF000000.toInt(),
                    EditorFonts.px(4f)
                )
                list.addText(right - labelWidth - pad, bottom - ImGui.getFontSize() - pad / 2f, 0xFFFFFFFF.toInt(), label)
            }
            if (card.hasProject) {
                val badge = EditorFonts.px(20f)
                val bx = x + EditorFonts.px(7f)
                val by = y + EditorFonts.px(7f)
                list.addRectFilled(bx, by, bx + badge, by + badge, 0xBF000000.toInt(), EditorFonts.px(4f))
                val iconSize = EditorFonts.px(11f)
                Icons.draw(list, Icon.KEYFRAME, bx + (badge - iconSize) / 2f, by + (badge - iconSize) / 2f, iconSize, 0xFFFFFFFF.toInt())
                if (hovered && ImGui.isMouseHoveringRect(bx, by, bx + badge, by + badge)) Widgets.hint("Has saved camera work")
            }
            val textY = y + thumbHeight + EditorFonts.px(7f)
            if (renaming == card.path) {
                ImGui.setCursorScreenPos(x, textY - EditorFonts.px(3f))
                renameField(card, cardWidth)
            } else {
                EditorFonts.with(EditorFonts.smallMedium) {
                    list.addText(x + EditorFonts.px(1f), textY, EditorTheme.TEXT.u32, Widgets.clip(card.title, cardWidth - EditorFonts.px(2f)))
                }
            }
            EditorFonts.with(EditorFonts.small) {
                val details = listOf(card.subtitle, formatSize(card.size)).filter { it.isNotEmpty() }.joinToString("    ")
                list.addText(
                    x + EditorFonts.px(1f),
                    textY + EditorFonts.px(17f),
                    EditorTheme.TEXT_DIM.u32,
                    Widgets.clip(details, cardWidth - EditorFonts.px(2f))
                )
            }
        } finally {
            ImGui.popID()
        }
    }

    private fun startRename(card: Card) {
        renaming = card.path
        renameBuffer.set(card.name.substringBeforeLast('.'))
        renameFocus = true
        selected = card.path
    }

    private fun commitRename(card: Card) {
        val target = renaming ?: return
        renaming = null
        val name = renameBuffer.get().trim()
        if (name.isEmpty() || name == card.name.substringBeforeLast('.')) return
        val renamed = context.host.replay.rename(target, name)
        if (renamed == null) {
            context.status("Could not rename ${card.name}: it may be open, or that name is taken")
        } else {
            context.status("Renamed to ${renamed.fileName}")
            selected = renamed
            infoCache.remove(target)
        }
        lastRefreshNanos = 0L
    }

    private fun open(path: Path) {
        context.status("Opening ${path.fileName}")
        context.host.later {
            if (!context.host.replay.open(path)) context.status(
                context.host.replay.lastError() ?: "Could not open ${path.fileName}"
            )
        }
    }

    private fun openFolder(path: Path) {
        runCatching {
            val target = if (Files.isDirectory(path)) path else path.toAbsolutePath().parent
            java.awt.Desktop.getDesktop().open(target.toFile())
        }.onFailure { context.status("Could not open folder: ${it.message}") }
    }

    private fun projects(width: Float, height: Float) {
        if (System.nanoTime() - lastProjectsRefreshNanos > PROJECTS_REFRESH_NANOS) {
            lastProjectsRefreshNanos = System.nanoTime()
            projectSummaries = context.host.replay.listProjects()
        }
        val query = filter.get().trim().lowercase()
        val visible = projectSummaries.filter { query.isEmpty() || it.name.lowercase().contains(query) }
            .sortedByDescending { it.modifiedEpochMillis }
        visibleCount = visible.size
        flush("##projects", width, height)
        if (visible.isEmpty()) {
            Widgets.emptyState(
                if (projectSummaries.isEmpty()) "No projects yet" else "Nothing matches",
                if (projectSummaries.isEmpty()) "Open a recording and your edits are saved here. Select several recordings to build a sequence." else "Try a different search",
                Icon.FOLDER
            )
        } else if (context.ui.libraryList) {
            projectsList(visible)
        } else {
            val target = context.ui.libraryThumbSize
            val gap = GAP
            val columns = maxOf(1, ((width + gap) / (target + gap)).toInt())
            val cardWidth = (width - gap * (columns - 1)) / columns
            val cardHeight = PROJECT_CARD_HEIGHT
            val startX = ImGui.getCursorPosX()
            val startY = ImGui.getCursorPosY()
            for ((index, summary) in visible.withIndex()) {
                ImGui.setCursorPos(startX + (index % columns) * (cardWidth + gap), startY + (index / columns) * (cardHeight + gap))
                drawProjectCard(summary, cardWidth)
            }
            val rows = (visible.size + columns - 1) / columns
            ImGui.setCursorPos(startX, startY + rows * (cardHeight + gap))
            ImGui.dummy(1f, 1f)
        }
        ImGui.endChild()
        if (!ImGui.getIO().wantTextInput) {
            val current = selected
            if (current != null && ImGui.isKeyPressed(ImGuiKey.Enter, false)) openProject(current)
            if (current != null && ImGui.isKeyPressed(ImGuiKey.Delete, false)) confirmDeleteProject = current
        }
    }

    private fun projectsList(visible: List<ProjectSummary>) {
        val flags =
            ImGuiTableFlags.RowBg or ImGuiTableFlags.ScrollY
        ImGui.pushStyleVar(ImGuiStyleVar.CellPadding, EditorFonts.px(8f), EditorFonts.px(4f))
        ImGui.pushStyleColor(ImGuiCol.HeaderHovered, EditorTheme.TEXT.u32(0.05f))
        ImGui.pushStyleColor(ImGuiCol.HeaderActive, EditorTheme.SELECTION_FILL.u32)
        ImGui.pushStyleColor(ImGuiCol.Header, EditorTheme.SELECTION_FILL.u32)
        val open = EditorFonts.with(EditorFonts.small) { ImGui.beginTable("lib-projects", 6, flags) }
        if (open) {
            EditorFonts.with(EditorFonts.small) {
                ImGui.tableSetupScrollFreeze(0, 1)
                ImGui.tableSetupColumn("Name", ImGuiTableColumnFlags.WidthStretch, 3f)
                ImGui.tableSetupColumn("Gameplay", ImGuiTableColumnFlags.WidthStretch, 2.4f)
                ImGui.tableSetupColumn("Length", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(76f))
                ImGui.tableSetupColumn("Keyframes", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(76f))
                ImGui.tableSetupColumn("Clips", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(56f))
                ImGui.tableSetupColumn("Modified", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(140f))
                ImGui.pushStyleColor(ImGuiCol.Text, EditorTheme.TEXT_MUTED.u32)
                ImGui.tableHeadersRow()
                ImGui.popStyleColor()
                for (summary in visible) projectRow(summary)
            }
            ImGui.endTable()
        }
        ImGui.popStyleColor(3)
        ImGui.popStyleVar()
    }

    private fun projectRow(summary: ProjectSummary) {
        ImGui.pushID(summary.path.toString())
        try {
            ImGui.tableNextRow(0, ROW_HEIGHT)
            ImGui.tableSetColumnIndex(0)
            val isSelected = selected == summary.path
            val cx = ImGui.getCursorScreenPosX()
            val cy = ImGui.getCursorScreenPosY()
            ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, EditorFonts.px(6f), 0f)
            ImGui.selectable(
                "##row",
                isSelected,
                ImGuiSelectableFlags.SpanAllColumns or ImGuiSelectableFlags.AllowDoubleClick or ImGuiSelectableFlags.AllowItemOverlap,
                0f,
                ROW_HEIGHT
            )
            ImGui.popStyleVar()
            val hovered = ImGui.isItemHovered()
            if (ImGui.isItemClicked(ImGuiMouseButton.Left) || ImGui.isItemClicked(ImGuiMouseButton.Right)) selected = summary.path
            if (hovered && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) openProject(summary.path)
            projectMenu(summary)
            val list = ImGui.getWindowDrawList()
            val iconSize = EditorFonts.px(12f)
            Icons.draw(
                list,
                if (summary.isSequence) Icon.LAYERS else Icon.FOLDER,
                cx,
                cy + (ROW_HEIGHT - iconSize) / 2f,
                iconSize,
                EditorTheme.TEXT_MUTED.u32
            )
            list.addText(
                cx + iconSize + EditorFonts.px(8f),
                cy + (ROW_HEIGHT - ImGui.getFontSize()) / 2f,
                EditorTheme.TEXT.u32,
                Widgets.clip(summary.name, ImGui.getContentRegionAvailX() - iconSize - EditorFonts.px(8f))
            )
            cell(1, projectSource(summary))
            cell(2, TimeFormat.clock(summary.lengthNanos).substringBefore('.'))
            cell(3, summary.keyframes.toString())
            cell(4, summary.clips.toString())
            cell(5, DISPLAY_FORMAT.format(Instant.ofEpochMilli(summary.modifiedEpochMillis).atZone(ZoneId.systemDefault())))
        } finally {
            ImGui.popID()
        }
    }

    private fun projectSource(summary: ProjectSummary): String =
        if (summary.isSequence) "${summary.segments.size} segments" else summary.segments.firstOrNull()?.gameplay?.fileName?.toString()
            ?.substringBeforeLast('.') ?: "No gameplay"

    private fun projectMenu(summary: ProjectSummary) {
        if (!Widgets.beginContextPopup("project-menu")) return
        if (ImGui.menuItem("Open", "Enter")) openProject(summary.path)
        if (ImGui.menuItem("Show in Folder")) openFolder(summary.path)
        ImGui.separator()
        if (ImGui.menuItem("Delete", "Del")) confirmDeleteProject = summary.path
        Widgets.endPopup()
    }

    private fun drawProjectCard(summary: ProjectSummary, cardWidth: Float) {
        ImGui.pushID(summary.path.toString())
        try {
            val list = ImGui.getWindowDrawList()
            val x = ImGui.getCursorScreenPosX()
            val y = ImGui.getCursorScreenPosY()
            val height = PROJECT_CARD_HEIGHT
            ImGui.setNextItemAllowOverlap()
            ImGui.invisibleButton("project", cardWidth, height)
            val hovered = ImGui.isItemHovered()
            val isSelected = selected == summary.path
            if (ImGui.isItemClicked(ImGuiMouseButton.Left) || ImGui.isItemClicked(ImGuiMouseButton.Right)) selected = summary.path
            if (hovered && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) openProject(summary.path)
            projectMenu(summary)
            val rounding = EditorFonts.px(5f)
            list.addRectFilled(
                x,
                y,
                x + cardWidth,
                y + height,
                if (hovered) EditorTheme.PANEL_RAISED.u32 else EditorTheme.PANEL.u32,
                rounding
            )
            list.addRect(
                x,
                y,
                x + cardWidth,
                y + height,
                if (isSelected) EditorTheme.SELECTION.u32 else EditorTheme.BORDER_SOFT.u32,
                rounding,
                0,
                if (isSelected) 2f else 1f
            )
            val inset = EditorFonts.px(10f)
            val thumbs = summary.segments.map { it.gameplay }.distinct().take(3)
            var thumbX = x + inset
            val thumbY = y + inset
            val thumbWidth = if (thumbs.size <= 1) (cardWidth - inset * 2f) else (cardWidth - inset * 2f - EditorFonts.px(4f) * (thumbs.size - 1)) / thumbs.size
            val thumbHeight = minOf(EditorFonts.px(74f), thumbWidth * 9f / 16f)
            for (gameplay in thumbs) {
                val texture = context.host.thumbnail(gameplay)
                if (texture != null) list.addImageRounded(
                    texture.toLong(),
                    thumbX,
                    thumbY,
                    thumbX + thumbWidth,
                    thumbY + thumbHeight,
                    0f,
                    0f,
                    1f,
                    1f,
                    0xFFFFFFFF.toInt(),
                    EditorFonts.px(3f)
                )
                else list.addRectFilled(thumbX, thumbY, thumbX + thumbWidth, thumbY + thumbHeight, EditorTheme.PANEL_SUNKEN.u32, EditorFonts.px(3f))
                thumbX += thumbWidth + EditorFonts.px(4f)
            }
            if (thumbs.isEmpty()) list.addRectFilled(
                x + inset,
                thumbY,
                x + cardWidth - inset,
                thumbY + thumbHeight,
                EditorTheme.PANEL_SUNKEN.u32,
                EditorFonts.px(3f)
            )
            val textY = thumbY + thumbHeight + EditorFonts.px(9f)
            EditorFonts.with(EditorFonts.smallMedium) {
                list.addText(x + inset, textY, EditorTheme.TEXT.u32, Widgets.clip(summary.name, cardWidth - inset * 2f))
            }
            EditorFonts.with(EditorFonts.small) {
                val kind = if (summary.isSequence) "${summary.segments.size} segments    ${TimeFormat.clock(summary.lengthNanos).substringBefore('.')}" else projectSource(summary)
                list.addText(x + inset, textY + EditorFonts.px(17f), EditorTheme.TEXT_MUTED.u32, Widgets.clip(kind, cardWidth - inset * 2f))
                list.addText(
                    x + inset,
                    textY + EditorFonts.px(32f),
                    EditorTheme.TEXT_DIM.u32,
                    Widgets.clip("${summary.keyframes} keyframes    ${summary.clips} clips    ${summary.markers} markers", cardWidth - inset * 2f)
                )
                list.addText(
                    x + inset,
                    textY + EditorFonts.px(47f),
                    EditorTheme.TEXT_DIM.u32,
                    DISPLAY_FORMAT.format(Instant.ofEpochMilli(summary.modifiedEpochMillis).atZone(ZoneId.systemDefault()))
                )
            }
        } finally {
            ImGui.popID()
        }
    }

    private fun openProject(path: Path) {
        context.status("Opening ${path.fileName}")
        context.host.later {
            if (!context.host.replay.openProject(path)) context.status(
                context.host.replay.lastError() ?: "Could not open ${path.fileName}"
            )
        }
    }

    private fun newProject(sources: List<Path>) {
        if (sources.isEmpty()) return
        val segments = sources.map { Segment(it, 0L, 0L, infoCache[it]?.second?.durationNanos ?: 0L) }
        val name = if (sources.size == 1) sources.first().fileName.toString()
            .substringBeforeLast('.') else "Sequence " + LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("MMM d HH-mm"))
        val created = context.host.replay.createProject(name, segments)
        marked.clear()
        if (created == null) {
            context.status("Could not create the project")
            return
        }
        lastProjectsRefreshNanos = 0L
        tab = 1
        openProject(created)
    }

    private fun modal(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onCancel: () -> Unit) =
        Dialog.confirm(title.substringBefore("##"), body, confirmLabel, onConfirm = onConfirm, onCancel = onCancel)

    private fun deleteProjectDialog() {
        val path = confirmDeleteProject ?: return
        modal("Delete project?##library", "Delete ${path.fileName}? The gameplay recordings stay.", "Delete", {
            if (context.host.replay.deleteProject(path)) context.status("Deleted ${path.fileName}") else context.status(
                "Could not delete ${path.fileName}"
            )
            lastProjectsRefreshNanos = 0L
            confirmDeleteProject = null
        }, { confirmDeleteProject = null })
    }

    private fun deleteManyDialog() {
        if (!confirmDeleteMany) return
        modal(
            "Delete recordings?##library",
            "Permanently delete ${marked.size} recordings? This cannot be undone.",
            "Delete ${marked.size}",
            {
                var deleted = 0
                for (path in marked.toList()) {
                    if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) deleted++
                    infoCache.remove(path)
                }
                context.status("Deleted $deleted recordings")
                marked.clear()
                lastRefreshNanos = 0L
                confirmDeleteMany = false
            },
            { confirmDeleteMany = false })
    }

    private fun deleteDialog() {
        val path = confirmDelete ?: return
        modal("Delete recording?##library", "Permanently delete ${path.fileName}? This cannot be undone.", "Delete", {
            runCatching { Files.deleteIfExists(path) }
                .onSuccess { context.status("Deleted ${path.fileName}") }
                .onFailure { context.status("Could not delete: ${it.message}") }
            infoCache.remove(path)
            lastRefreshNanos = 0L
            confirmDelete = null
        }, { confirmDelete = null })
    }

    private fun refresh() {
        val projects = context.host.projectsDirectory
        val current = context.host.replay.currentPath()
        cards = context.host.replay.listRecordings().map { path ->
            val name = path.fileName.toString()
            val size = runCatching { Files.size(path) }.getOrDefault(0L)
            val cached = infoCache[path]
            val info = if (cached != null && cached.first == size) cached.second else {
                val fresh = if (path == current) cached?.second else context.host.replay.describe(path)
                infoCache[path] = size to fresh
                fresh
            }
            val project = projects.resolve(name.substringBeforeLast('.') + ".rcproj")
            val started = info?.startEpochMillis?.takeIf { it > 0L }
            val recorded =
                started?.let { DISPLAY_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
                    ?: recordedDate(name)
            val sortKey = started ?: runCatching {
                LocalDateTime.parse(name.substringBeforeLast('.'), FILE_FORMAT).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            }.getOrDefault(0L)
            Card(path, name, recorded, sortKey, size, Files.exists(project), info)
        }
        infoCache.keys.retainAll(cards.map { it.path }.toSet())
    }

    private fun recordedDate(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        return runCatching { LocalDateTime.parse(base, FILE_FORMAT).format(DISPLAY_FORMAT) }.getOrDefault(base)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1L shl 10 -> String.format("%.0f kB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private companion object {
        val SIDEBAR_WIDTH: Float get() = EditorFonts.px(196f)
        val BAR_HEIGHT: Float get() = EditorFonts.px(38f)
        val FOOTER_HEIGHT: Float get() = EditorFonts.px(30f)
        val STATUS_HEIGHT: Float get() = EditorFonts.px(84f)
        val ROW_HEIGHT: Float get() = EditorFonts.px(24f)
        val GAP: Float get() = EditorFonts.px(14f)
        val THUMB_MIN: Float get() = EditorFonts.px(150f)
        val THUMB_MAX: Float get() = EditorFonts.px(400f)
        val REFRESH_NANOS = Nanos.ofSeconds(2)
        val PROJECTS_REFRESH_NANOS = Nanos.ofSeconds(5)
        val PROJECT_CARD_HEIGHT: Float get() = EditorFonts.px(158f)
        val FILE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        val DISPLAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy  HH:mm")
    }
}
