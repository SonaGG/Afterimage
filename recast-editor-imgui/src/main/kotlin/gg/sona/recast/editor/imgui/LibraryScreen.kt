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
        val subtitle: String
            get() = listOfNotNull(
                info?.server?.takeIf { it.isNotBlank() },
                info?.player?.takeIf { it.isNotBlank() }?.let { "as $it" }).joinToString("    ")
    }

    private enum class Sort(val label: String) {
        NEWEST("Newest"), OLDEST("Oldest"), NAME("Name"), LONGEST("Longest"), SIZE(
            "Largest"
        )
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
        if (ImGui.begin("##library", flags)) {
            sidebar()
            ImGui.sameLine(0f, 0f)
            content(frame)
        }
        ImGui.end()
        ImGui.popStyleVar()
        ImGui.popStyleColor()
        deleteDialog()
        deleteManyDialog()
        deleteProjectDialog()
    }

    private fun sidebar() {
        val width = SIDEBAR_WIDTH
        ImGui.pushStyleColor(ImGuiCol.ChildBg, EditorTheme.PANEL.u32)
        ImGui.beginChild("##library-sidebar", width, 0f, false, ImGuiWindowFlags.NoScrollbar)
        ImGui.popStyleColor()
        val list = ImGui.getWindowDrawList()
        val x = ImGui.getWindowPosX()
        val y = ImGui.getWindowPosY()
        list.addLine(x + width - 1f, y, x + width - 1f, y + ImGui.getWindowHeight(), EditorTheme.SEPARATOR.u32, 1f)
        ImGui.setCursorPos(EditorFonts.px(18f), EditorFonts.px(20f))
        EditorFonts.with(EditorFonts.title) { ImGui.textUnformatted("Recast") }
        ImGui.dummy(0f, EditorFonts.px(12f))
        sidebarItem("Recordings", Icon.FILM, 0, "${cards.size}")
        sidebarItem("Projects", Icon.FOLDER, 1, "${projectSummaries.size}")
        val status = context.host.recording.status()
        ImGui.setCursorPos(EditorFonts.px(14f), ImGui.getWindowHeight() - EditorFonts.px(88f))
        ImGui.beginGroup()
        when {
            status.recording -> {
                Widgets.pill("REC ${TimeFormat.clock(status.elapsedNanos).substringBefore('.')}", EditorTheme.RECORD)
                if (Widgets.dangerButton("Stop recording", width - EditorFonts.px(28f))) context.host.recording.stop()
            }

            status.connected -> {
                Widgets.pill("Connected", EditorTheme.SUCCESS)
                if (Widgets.accentButton("Start recording", width - EditorFonts.px(28f))) context.host.recording.start()
            }

            else -> Widgets.smallText("Join a world to record", EditorTheme.TEXT_DIM.u32)
        }
        ImGui.endGroup()
        ImGui.endChild()
    }

    private fun sidebarItem(label: String, icon: Icon, index: Int, trailing: String) {
        ImGui.setCursorPosX(EditorFonts.px(10f))
        val active = tab == index
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, EditorFonts.px(2f))
        val pressed = Widgets.row("side$index", EditorFonts.px(28f), active) { x, y, width, hovered ->
            val list = ImGui.getWindowDrawList()
            val iconSize = EditorFonts.px(15f)
            Icons.draw(
                list,
                icon,
                x + EditorFonts.px(10f),
                y + (EditorFonts.px(28f) - iconSize) / 2f,
                iconSize,
                if (active) EditorTheme.ACCENT_TEXT.u32 else EditorTheme.TEXT_MUTED.u32
            )
            val font = if (active) EditorFonts.bodyMedium else EditorFonts.body
            list.addText(
                font,
                ImGui.getFontSize(),
                x + EditorFonts.px(32f),
                y + (EditorFonts.px(28f) - ImGui.getFontSize()) / 2f,
                EditorTheme.TEXT.u32,
                label
            )
            EditorFonts.with(EditorFonts.small) {
                val trailingWidth = Widgets.textWidth(trailing)
                list.addText(
                    x + width - trailingWidth - EditorFonts.px(12f),
                    y + (EditorFonts.px(28f) - ImGui.getFontSize()) / 2f,
                    EditorTheme.TEXT_DIM.u32,
                    trailing
                )
            }
        }
        ImGui.popStyleVar()
        if (pressed) {
            tab = index
            marked.clear()
        }
    }

    private fun content(frame: FrameContext) {
        ImGui.beginChild("##library-content", 0f, 0f, false, ImGuiWindowFlags.NoScrollbar)
        val padding = EditorFonts.px(28f)
        ImGui.setCursorPos(padding, EditorFonts.px(22f))
        EditorFonts.with(EditorFonts.title) { ImGui.textUnformatted(if (tab == 0) "Recordings" else "Projects") }
        val searchWidth = EditorFonts.px(260f)
        ImGui.sameLine(
            ImGui.getWindowWidth() - padding - searchWidth - (if (tab == 0) EditorFonts.px(180f) else EditorFonts.px(
                40f
            ))
        )
        ImGui.setCursorPosY(EditorFonts.px(24f))
        Widgets.search(
            "##library-search",
            filter,
            if (tab == 0) "Search recordings" else "Search projects",
            searchWidth
        )
        if (tab == 0) {
            ImGui.sameLine(0f, EditorFonts.px(8f))
            sortPopup()
        }
        ImGui.sameLine(0f, EditorFonts.px(8f))
        if (Widgets.iconButton("lib-refresh", Icon.REFRESH, ImGui.getFrameHeight(), "Refresh")) {
            lastRefreshNanos = 0L
            lastProjectsRefreshNanos = 0L
        }
        ImGui.sameLine(0f, EditorFonts.px(4f))
        if (Widgets.iconButton("lib-folder", Icon.FOLDER, ImGui.getFrameHeight(), "Show in Finder or Explorer")) {
            if (tab == 0) cards.firstOrNull()
                ?.let { openFolder(it.path) } else openFolder(context.host.projectsDirectory)
        }
        ImGui.dummy(0f, EditorFonts.px(6f))
        notices(padding)
        if (tab == 1) projectsGrid(padding) else recordingsGrid(padding)
        ImGui.endChild()
    }

    private fun sortPopup() {
        val label = sort.label
        val width = EditorFonts.px(96f)
        val height = ImGui.getFrameHeight()
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
        list.addText(x + EditorFonts.px(9f), y + (height - ImGui.getFontSize()) / 2f, EditorTheme.TEXT.u32, label)
        Icons.draw(
            list,
            Icon.CHEVRON_DOWN,
            x + width - EditorFonts.px(16f),
            y + (height - EditorFonts.px(9f)) / 2f,
            EditorFonts.px(9f),
            EditorTheme.TEXT_DIM.u32
        )
        if (pressed) ImGui.openPopup("lib-sort-menu")
        if (ImGui.beginPopup("lib-sort-menu")) {
            for (option in Sort.entries) if (ImGui.menuItem(option.label, "", sort == option)) sort = option
            ImGui.endPopup()
        }
    }

    private fun notices(padding: Float) {
        context.host.replay.lastError()?.let {
            ImGui.setCursorPosX(padding)
            Widgets.smallText(it, EditorTheme.WARNING.u32)
        }
        context.exports?.queue()?.handles()
            ?.filter { it.state == ExportState.RUNNING || it.state == ExportState.QUEUED }?.forEach { handle ->
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
            Widgets.pill("Building sequence", EditorTheme.ACCENT)
        }
    }

    private fun recordingsGrid(padding: Float) {
        val query = filter.get().trim().lowercase()
        val visible = cards.filter {
            query.isEmpty() || it.title.lowercase().contains(query) || it.name.lowercase()
                .contains(query) || it.subtitle.lowercase().contains(query)
        }.sortedWith(comparator())
        ImGui.setCursorPosX(padding)
        val available = ImGui.getContentRegionAvailX() - padding
        val reserved = if (marked.size >= 2) EditorFonts.px(64f) else EditorFonts.px(12f)
        ImGui.beginChild("##grid", available, ImGui.getContentRegionAvailY() - reserved, false)
        if (visible.isEmpty()) {
            Widgets.emptyState(
                if (cards.isEmpty()) "No recordings yet" else "Nothing matches",
                if (cards.isEmpty()) "Join a world and start recording, or turn on auto record in Settings" else "Try a different search",
                Icon.FILM
            )
        } else {
            val columns = maxOf(1, ((available + GAP) / (CARD_WIDTH + GAP)).toInt())
            val cardWidth = (available - GAP * (columns - 1)) / columns
            val cardHeight = cardWidth * 9f / 16f + EditorFonts.px(58f)
            val startX = ImGui.getCursorPosX()
            val startY = ImGui.getCursorPosY()
            for ((index, card) in visible.withIndex()) {
                ImGui.setCursorPos(
                    startX + (index % columns) * (cardWidth + GAP),
                    startY + (index / columns) * (cardHeight + GAP)
                )
                drawCard(card, cardWidth, cardHeight)
            }
            val rows = (visible.size + columns - 1) / columns
            ImGui.setCursorPos(startX, startY + rows * (cardHeight + GAP))
            ImGui.dummy(1f, 1f)
        }
        ImGui.endChild()
        if (!ImGui.getIO().wantTextInput && renaming == null) {
            val current = selected
            if (current != null && ImGui.isKeyPressed(ImGuiKey.Enter, false)) open(current)
            if (current != null && ImGui.isKeyPressed(ImGuiKey.Delete, false)) if (marked.size >= 2) confirmDeleteMany =
                true else confirmDelete = current
        }
        if (marked.size >= 2) selectionBar(padding)
    }

    private fun selectionBar(padding: Float) {
        ImGui.setCursorPosX(padding)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val width = ImGui.getContentRegionAvailX() - padding
        val height = EditorFonts.px(44f)
        ImGui.getWindowDrawList()
            .addRectFilled(x, y, x + width, y + height, EditorTheme.PANEL_RAISED.u32, EditorFonts.px(10f))
        ImGui.setCursorScreenPos(x + EditorFonts.px(14f), y + (height - ImGui.getFrameHeight()) / 2f)
        ImGui.alignTextToFramePadding()
        EditorFonts.with(EditorFonts.bodyMedium) { ImGui.textUnformatted("${marked.size} selected") }
        ImGui.sameLine(0f, EditorFonts.px(16f))
        if (Widgets.accentButton("New project from selection")) newProject(marked.toList())
        Widgets.tooltip("Plays the selected recordings back to back in the order you picked them")
        ImGui.sameLine()
        if (Widgets.dangerButton("Delete")) confirmDeleteMany = true
        ImGui.sameLine()
        if (Widgets.ghostButton("Clear")) marked.clear()
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
            if (ImGui.isItemClicked(ImGuiMouseButton.Left)) {
                selected = card.path
                if (ImGui.getIO().keyCtrl) {
                    if (!marked.remove(card.path)) marked.add(card.path)
                } else if (marked.isNotEmpty() && card.path !in marked) {
                    marked.clear()
                }
            }
            if (ImGui.isItemClicked(ImGuiMouseButton.Right)) selected = card.path
            if (hovered && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) open(card.path)
            if (ImGui.beginPopupContextItem("card-menu")) {
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
                ImGui.endPopup()
            }
            if (isSelected && renaming == null && !ImGui.getIO().wantTextInput && ImGui.isKeyPressed(
                    ImGuiKey.F2,
                    false
                )
            ) startRename(card)
            val rounding = EditorFonts.px(8f)
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
                    if (hovered || isSelected) 0xFFFFFFFF.toInt() else 0xFFE8E8E8.toInt(),
                    rounding
                )
            } else {
                list.addRectFilled(x, y, x + cardWidth, y + thumbHeight, EditorTheme.PANEL_RAISED.u32, rounding)
                val iconSize = EditorFonts.px(30f)
                Icons.draw(
                    list,
                    Icon.FILM,
                    x + cardWidth / 2f - iconSize / 2f,
                    y + thumbHeight / 2f - iconSize / 2f,
                    iconSize,
                    EditorTheme.TEXT_DIM.u32
                )
            }
            if (isSelected) list.addRect(
                x - 2f,
                y - 2f,
                x + cardWidth + 2f,
                y + thumbHeight + 2f,
                EditorTheme.ACCENT.u32,
                rounding + 2f,
                0,
                2.5f
            )
            else if (hovered) list.addRect(
                x,
                y,
                x + cardWidth,
                y + thumbHeight,
                EditorTheme.TEXT.u32(0.25f),
                rounding,
                0,
                1f
            )
            else list.addRect(x, y, x + cardWidth, y + thumbHeight, EditorTheme.BORDER_SOFT.u32(0.1f), rounding, 0, 1f)
            card.info?.let { info ->
                EditorFonts.with(EditorFonts.smallMedium) {
                    val label = TimeFormat.clock(info.durationNanos).substringBefore('.')
                    val labelWidth = Widgets.textWidth(label)
                    val pad = EditorFonts.px(5f)
                    val right = x + cardWidth - EditorFonts.px(8f)
                    val bottom = y + thumbHeight - EditorFonts.px(8f)
                    list.addRectFilled(
                        right - labelWidth - pad * 2f,
                        bottom - ImGui.getFontSize() - pad,
                        right,
                        bottom,
                        0xB3000000.toInt(),
                        EditorFonts.px(5f)
                    )
                    list.addText(
                        right - labelWidth - pad,
                        bottom - ImGui.getFontSize() - pad / 2f,
                        0xFFFFFFFF.toInt(),
                        label
                    )
                }
            }
            if (card.hasProject) {
                val badge = EditorFonts.px(8f)
                list.addCircleFilled(
                    x + EditorFonts.px(12f),
                    y + EditorFonts.px(12f),
                    badge / 2f,
                    EditorTheme.ACCENT.u32,
                    12
                )
                if (hovered && ImGui.isMouseHoveringRect(
                        x,
                        y,
                        x + EditorFonts.px(24f),
                        y + EditorFonts.px(24f)
                    )
                ) Widgets.hint("Has saved camera work")
            }
            val textY = y + thumbHeight + EditorFonts.px(9f)
            if (renaming == card.path) {
                ImGui.setCursorScreenPos(x, textY - EditorFonts.px(3f))
                ImGui.setNextItemWidth(cardWidth)
                if (renameFocus) {
                    ImGui.setKeyboardFocusHere()
                    renameFocus = false
                }
                if (ImGui.inputText(
                        "##rename",
                        renameBuffer,
                        ImGuiInputTextFlags.EnterReturnsTrue or ImGuiInputTextFlags.AutoSelectAll
                    )
                ) commitRename(card)
                if (ImGui.isItemDeactivated() && renaming == card.path) {
                    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) renaming = null else commitRename(card)
                }
            } else {
                EditorFonts.with(EditorFonts.bodyMedium) {
                    list.addText(
                        x + EditorFonts.px(2f),
                        textY,
                        EditorTheme.TEXT.u32,
                        Widgets.clip(card.title, cardWidth - EditorFonts.px(4f))
                    )
                }
            }
            EditorFonts.with(EditorFonts.small) {
                val details =
                    listOf(card.subtitle, formatSize(card.size)).filter { it.isNotEmpty() }.joinToString("    ")
                list.addText(
                    x + EditorFonts.px(2f),
                    textY + EditorFonts.px(20f),
                    EditorTheme.TEXT_MUTED.u32,
                    Widgets.clip(details, cardWidth - EditorFonts.px(4f))
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

    private fun projectsGrid(padding: Float) {
        if (System.nanoTime() - lastProjectsRefreshNanos > PROJECTS_REFRESH_NANOS) {
            lastProjectsRefreshNanos = System.nanoTime()
            projectSummaries = context.host.replay.listProjects()
        }
        val query = filter.get().trim().lowercase()
        val visible = projectSummaries.filter { query.isEmpty() || it.name.lowercase().contains(query) }
        ImGui.setCursorPosX(padding)
        val available = ImGui.getContentRegionAvailX() - padding
        ImGui.beginChild("##projects", available, ImGui.getContentRegionAvailY() - EditorFonts.px(12f), false)
        if (visible.isEmpty()) {
            Widgets.emptyState(
                if (projectSummaries.isEmpty()) "No projects yet" else "Nothing matches",
                if (projectSummaries.isEmpty()) "Open a recording and your edits are saved here. Select several recordings to build a sequence." else "Try a different search",
                Icon.FOLDER
            )
        } else {
            val columns = maxOf(1, ((available + GAP) / (CARD_WIDTH + GAP)).toInt())
            val cardWidth = (available - GAP * (columns - 1)) / columns
            val height = PROJECT_CARD_HEIGHT
            val startX = ImGui.getCursorPosX()
            val startY = ImGui.getCursorPosY()
            for ((index, summary) in visible.withIndex()) {
                ImGui.setCursorPos(
                    startX + (index % columns) * (cardWidth + GAP),
                    startY + (index / columns) * (height + GAP)
                )
                drawProjectCard(summary, cardWidth)
            }
            val rows = (visible.size + columns - 1) / columns
            ImGui.setCursorPos(startX, startY + rows * (height + GAP))
            ImGui.dummy(1f, 1f)
        }
        ImGui.endChild()
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
            if (ImGui.isItemClicked(ImGuiMouseButton.Left) || ImGui.isItemClicked(ImGuiMouseButton.Right)) selected =
                summary.path
            if (hovered && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) openProject(summary.path)
            if (ImGui.beginPopupContextItem("project-menu")) {
                if (ImGui.menuItem("Open")) openProject(summary.path)
                if (ImGui.menuItem("Show in Folder")) openFolder(summary.path)
                ImGui.separator()
                if (ImGui.menuItem("Delete")) confirmDeleteProject = summary.path
                ImGui.endPopup()
            }
            val rounding = EditorFonts.px(8f)
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
                if (isSelected) EditorTheme.ACCENT.u32 else EditorTheme.BORDER_SOFT.u32(0.1f),
                rounding,
                0,
                if (isSelected) 2f else 1f
            )
            val inset = EditorFonts.px(12f)
            val thumbs = summary.segments.map { it.gameplay }.distinct().take(3)
            var thumbX = x + inset
            val thumbY = y + inset
            val thumbWidth = minOf(EditorFonts.px(96f), (cardWidth - inset * 2f - EditorFonts.px(12f)) / 3f)
            val thumbHeight = thumbWidth * 9f / 16f
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
                    EditorFonts.px(4f)
                )
                else list.addRectFilled(
                    thumbX,
                    thumbY,
                    thumbX + thumbWidth,
                    thumbY + thumbHeight,
                    EditorTheme.PANEL_SUNKEN.u32,
                    EditorFonts.px(4f)
                )
                thumbX += thumbWidth + EditorFonts.px(6f)
            }
            if (thumbs.isEmpty()) list.addRectFilled(
                x + inset,
                thumbY,
                x + inset + thumbWidth,
                thumbY + thumbHeight,
                EditorTheme.PANEL_SUNKEN.u32,
                EditorFonts.px(4f)
            )
            val textY = thumbY + thumbHeight + EditorFonts.px(10f)
            EditorFonts.with(EditorFonts.bodyMedium) {
                list.addText(
                    x + inset,
                    textY,
                    EditorTheme.TEXT.u32,
                    Widgets.clip(summary.name, cardWidth - inset * 2f)
                )
            }
            EditorFonts.with(EditorFonts.small) {
                val kind = if (summary.isSequence) "${summary.segments.size} segments    ${
                    TimeFormat.clock(summary.lengthNanos).substringBefore('.')
                }" else summary.segments.firstOrNull()?.gameplay?.fileName?.toString()?.substringBeforeLast('.')
                    ?: "No gameplay"
                list.addText(
                    x + inset,
                    textY + EditorFonts.px(20f),
                    EditorTheme.TEXT_MUTED.u32,
                    Widgets.clip(kind, cardWidth - inset * 2f)
                )
                list.addText(
                    x + inset,
                    textY + EditorFonts.px(37f),
                    EditorTheme.TEXT_DIM.u32,
                    "${summary.keyframes} keyframes    ${summary.clips} clips    ${summary.markers} markers"
                )
                list.addText(
                    x + inset,
                    textY + EditorFonts.px(54f),
                    EditorTheme.TEXT_DIM.u32,
                    DISPLAY_FORMAT.format(
                        Instant.ofEpochMilli(summary.modifiedEpochMillis).atZone(ZoneId.systemDefault())
                    )
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
        val SIDEBAR_WIDTH: Float get() = EditorFonts.px(200f)
        val CARD_WIDTH: Float get() = EditorFonts.px(272f)
        val GAP: Float get() = EditorFonts.px(20f)
        val REFRESH_NANOS = Nanos.ofSeconds(2)
        val PROJECTS_REFRESH_NANOS = Nanos.ofSeconds(5)
        val PROJECT_CARD_HEIGHT: Float get() = EditorFonts.px(160f)
        val FILE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        val DISPLAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy  HH:mm")
    }
}
