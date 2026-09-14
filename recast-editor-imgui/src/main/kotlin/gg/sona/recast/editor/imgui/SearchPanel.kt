package gg.sona.recast.editor.imgui

import gg.sona.recast.clip.Clip
import gg.sona.recast.clip.ClipOrigin
import gg.sona.recast.core.time.Nanos
import gg.sona.recast.editor.EditorSession
import gg.sona.recast.editor.MarkerKind
import gg.sona.recast.editor.Selection
import gg.sona.recast.editor.TimelineMarker
import gg.sona.recast.editor.commands.AddClip
import gg.sona.recast.editor.commands.AddMarker
import gg.sona.recast.editor.commands.SetInOutPoints
import gg.sona.recast.index.IndexEventKind
import gg.sona.recast.index.query.ReplaySearch
import gg.sona.recast.index.query.SearchHit
import gg.sona.recast.index.query.SearchResult
import imgui.ImGui
import imgui.flag.*
import imgui.type.ImString
import java.util.*

class SearchPanel(private val context: EditorContext) : AbstractPanel("Search", DockArea.RIGHT, Icon.SEARCH) {

    private val query = ImString("", 256)
    private var result: SearchResult? = null
    private var resultFor: ReplaySearch? = null
    private var selected = -1
    private var pendingQuery: String? = null

    override fun content(frame: FrameContext) {
        val session = context.session
        val replay = session?.replay
        if (session == null || replay == null) {
            Widgets.emptyState("No replay open", "Search finds kills, fights, explosions and more", Icon.SEARCH)
            return
        }
        context.searchRequest?.let {
            pendingQuery = it
            context.searchRequest = null
        }
        val events = session.events
        val search = events.search
        if (search != null && resultFor !== search) {
            resultFor = search
            result = null
            selected = -1
            if (query.get().isNotBlank()) run(search)
        }
        pendingQuery?.let {
            query.set(it)
            pendingQuery = null
            if (search != null) run(search)
        }
        val submitted = Widgets.search(
            "##query",
            query,
            "Search the replay: kill by:me, explosion near:me, near(me, Steve) < 5",
            flags = ImGuiInputTextFlags.EnterReturnsTrue
        )
        if (submitted && search != null) run(search)
        val current = result
        when {
            search == null -> {
                ImGui.dummy(0f, EditorFonts.px(4f))
                Widgets.progress(events.progress.toFloat(), -1f, "Indexing ${(events.progress * 100).toInt()}%")
                Widgets.smallText("Search is available once the recording is indexed.", EditorTheme.TEXT_DIM.u32)
            }

            current == null || query.get().isBlank() -> examples()
            current.error != null -> error(current)
            else -> results(session, current)
        }
    }

    private fun run(search: ReplaySearch) {
        result = search.run(query.get())
        selected = -1
    }

    private fun examples() {
        ImGui.dummy(0f, EditorFonts.px(6f))
        val names = context.session?.events?.index?.playerNames.orEmpty()
        if (names.isNotEmpty()) {
            Widgets.header("Players")
            var first = true
            for (name in names.take(24)) {
                if (!first) ImGui.sameLine(0f, EditorFonts.px(6f))
                first = false
                if (chip(name)) pendingQuery = "event player:$name"
                if (ImGui.getContentRegionAvailX() < EditorFonts.px(90f)) first = true
            }
            ImGui.dummy(0f, EditorFonts.px(8f))
        }
        Widgets.header("Try")
        for ((text, description) in ReplaySearch.EXAMPLES) {
            ImGui.pushID(text)
            try {
                if (chip(text)) pendingQuery = text
                ImGui.sameLine(0f, EditorFonts.px(10f))
                Widgets.smallText(description, EditorTheme.TEXT_DIM.u32)
            } finally {
                ImGui.popID()
            }
        }
    }

    private fun chip(text: String): Boolean {
        val padX = EditorFonts.px(9f)
        val height = ImGui.getFrameHeight() * 0.85f
        val width = Widgets.textWidth(text) + padX * 2f
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        ImGui.invisibleButton("##chip-$text", width, height)
        val hovered = ImGui.isItemHovered()
        val list = ImGui.getWindowDrawList()
        list.addRectFilled(
            x,
            y,
            x + width,
            y + height,
            if (hovered) EditorTheme.CONTROL_HOVER.u32 else EditorTheme.CONTROL.u32,
            height / 2f
        )
        list.addText(x + padX, y + (height - ImGui.getFontSize()) / 2f, EditorTheme.TEXT.u32, text)
        if (hovered) Widgets.cursorHand()
        return ImGui.isItemClicked(ImGuiMouseButton.Left)
    }

    private fun error(current: SearchResult) {
        ImGui.dummy(0f, EditorFonts.px(6f))
        Widgets.iconText(Icon.WARNING, current.error ?: "Bad query", EditorTheme.WARNING.u32, EditorTheme.WARNING.u32)
        if (current.errorPosition >= 0 && current.errorPosition <= current.query.length) {
            EditorFonts.with(EditorFonts.timecode) {
                Widgets.smallText(current.query, EditorTheme.TEXT_MUTED.u32)
                Widgets.smallText(" ".repeat(current.errorPosition) + "^", EditorTheme.WARNING.u32)
            }
        }
    }

    private fun results(session: EditorSession, current: SearchResult) {
        val replay = session.replay ?: return
        EditorTheme.pushToolbarStyle()
        try {
            val summary = when {
                current.hits.isEmpty() -> "No results"
                current.truncated -> "First ${current.hits.size} results"
                current.hits.size == 1 -> "1 result"
                else -> "${current.hits.size} results"
            }
            Widgets.smallText("$summary in ${current.elapsedMillis} ms", EditorTheme.TEXT_DIM.u32)
            if (current.hits.isNotEmpty()) {
                ImGui.sameLine(0f, EditorFonts.px(14f))
                if (Widgets.ghostButton("Markers from results")) markersFromResults(session, current)
                Widgets.tooltip("Add a marker at every result")
                ImGui.sameLine()
                if (Widgets.ghostButton("Clips from results")) clipsFromResults(session, current)
                Widgets.tooltip("Add a clip around every result, ready for a montage")
            }
        } finally {
            EditorTheme.popToolbarStyle()
        }
        if (current.hits.isEmpty()) {
            Widgets.emptyState("Nothing matched", "Try a wider filter or another player", Icon.SEARCH)
            return
        }
        val spans = current.hits.any { it.isSpan }
        val flags = ImGuiTableFlags.RowBg or ImGuiTableFlags.ScrollY or ImGuiTableFlags.BordersInnerV
        if (ImGui.beginTable("results", 3, flags)) {
            ImGui.tableSetupScrollFreeze(0, 1)
            ImGui.tableSetupColumn("Time", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(if (spans) 150f else 84f))
            ImGui.tableSetupColumn("What", ImGuiTableColumnFlags.WidthStretch)
            ImGui.tableSetupColumn("Detail", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(140f))
            ImGui.tableHeadersRow()
            for ((index, hit) in current.hits.withIndex()) {
                ImGui.pushID(index)
                try {
                    ImGui.tableNextRow()
                    ImGui.tableNextColumn()
                    val isSelected = index == selected
                    val atPlayhead = replay.positionNanos in hit.nanos..maxOf(hit.nanos, hit.endNanos)
                    ImGui.setNextItemAllowOverlap()
                    if (ImGui.selectable(
                            "##row",
                            isSelected,
                            ImGuiSelectableFlags.SpanAllColumns or ImGuiSelectableFlags.AllowItemOverlap,
                            0f,
                            ROW_HEIGHT
                        )
                    ) {
                        selected = index
                        replay.seek(hit.nanos)
                        context.status(hit.label)
                    }
                    if (ImGui.beginPopupContextItem("hit-menu")) {
                        if (ImGui.menuItem("Go there")) replay.seek(hit.nanos)
                        if (hit.hasPosition && ImGui.menuItem("Frame in scene")) context.host.camera.frame(
                            hit.x,
                            hit.y,
                            hit.z,
                            6.0
                        )
                        ImGui.separator()
                        if (ImGui.menuItem("Add marker")) addMarker(session, hit)
                        if (ImGui.menuItem("Add clip around")) addClip(session, hit)
                        if (ImGui.menuItem("Set in/out")) session.execute(
                            SetInOutPoints(
                                if (hit.isSpan) hit.nanos else maxOf(replay.startNanos, hit.nanos - PRE_ROLL),
                                if (hit.isSpan) hit.endNanos else minOf(replay.endNanos, hit.nanos + POST_ROLL)
                            )
                        )
                        ImGui.endPopup()
                    }
                    ImGui.sameLine(0f, 0f)
                    val timeText =
                        if (hit.isSpan) "${TimeFormat.clock(hit.nanos)} - ${TimeFormat.clock(hit.endNanos)}" else TimeFormat.clock(
                            hit.nanos
                        )
                    Widgets.tabular(
                        timeText,
                        EditorFonts.timecode,
                        if (atPlayhead) EditorTheme.ACCENT_TEXT.u32 else EditorTheme.TEXT.u32
                    )
                    ImGui.tableNextColumn()
                    val color = hit.kind?.let { kindColor(it) } ?: EditorTheme.TEXT_MUTED.u32
                    ImGui.alignTextToFramePadding()
                    Icons.draw(
                        ImGui.getWindowDrawList(),
                        kindIcon(hit.kind),
                        ImGui.getCursorScreenPosX(),
                        ImGui.getCursorScreenPosY() + (ROW_HEIGHT - EditorFonts.px(13f)) / 2f,
                        EditorFonts.px(13f),
                        color
                    )
                    ImGui.setCursorPosX(ImGui.getCursorPosX() + EditorFonts.px(19f))
                    ImGui.textUnformatted(Widgets.clip(hit.label, ImGui.getContentRegionAvailX()))
                    ImGui.tableNextColumn()
                    ImGui.alignTextToFramePadding()
                    Widgets.smallText(hit.detail, EditorTheme.TEXT_DIM.u32, clipToWidth = true)
                } finally {
                    ImGui.popID()
                }
            }
            ImGui.endTable()
        }
    }

    private fun addMarker(session: EditorSession, hit: SearchHit) {
        val marker = TimelineMarker(
            UUID.randomUUID(),
            hit.nanos,
            hit.label,
            hit.kind?.let { kindRgb(it) } ?: 0xA6A6AB,
            if (hit.kind != null) MarkerKind.EVENT else MarkerKind.NOTE
        )
        session.execute(AddMarker(marker))
        session.selection = Selection(markerIds = setOf(marker.id))
    }

    private fun addClip(session: EditorSession, hit: SearchHit) {
        val project = session.project
        val clip = if (hit.isSpan) Clip(
            UUID.randomUUID(),
            project.recording,
            project.sessionId,
            hit.nanos,
            hit.endNanos,
            hit.label
        ) else Clip.around(
            project.recording,
            project.sessionId,
            hit.nanos,
            PRE_ROLL,
            POST_ROLL,
            hit.label,
            ClipOrigin.MANUAL
        )
        session.execute(AddClip(clip))
    }

    private fun markersFromResults(session: EditorSession, current: SearchResult) {
        val hits = current.hits.take(MAX_BATCH)
        for (hit in hits) session.execute(
            AddMarker(
                TimelineMarker(
                    UUID.randomUUID(),
                    hit.nanos,
                    hit.label,
                    hit.kind?.let { kindRgb(it) } ?: 0xA6A6AB,
                    if (hit.kind != null) MarkerKind.EVENT else MarkerKind.NOTE))
        )
        context.toast("Added ${hits.size} marker${if (hits.size == 1) "" else "s"}")
    }

    private fun clipsFromResults(session: EditorSession, current: SearchResult) {
        val hits = current.hits.take(MAX_BATCH)
        for (hit in hits) addClip(session, hit)
        context.toast("Added ${hits.size} clip${if (hits.size == 1) "" else "s"}")
        context.openPanel("Clips")
    }

    private fun kindIcon(kind: IndexEventKind?): Icon = when (kind) {
        null -> Icon.CLOCK
        IndexEventKind.KILL, IndexEventKind.ATTACK, IndexEventKind.SWING, IndexEventKind.CRIT -> Icon.TARGET
        IndexEventKind.DEATH, IndexEventKind.HURT -> Icon.WARNING
        IndexEventKind.EXPLOSION -> Icon.WAVE
        IndexEventKind.PROJECTILE_SPAWN, IndexEventKind.PROJECTILE_END -> Icon.ARROW_RIGHT
        IndexEventKind.CHAT, IndexEventKind.TITLE, IndexEventKind.SCOREBOARD, IndexEventKind.BOSS, IndexEventKind.ACHIEVEMENT -> Icon.TAG
        IndexEventKind.JOIN, IndexEventKind.LEAVE, IndexEventKind.RESPAWN, IndexEventKind.DIMENSION -> Icon.PERSON
        IndexEventKind.MARKER -> Icon.MARKER
        IndexEventKind.SOUND -> Icon.VOLUME
        else -> Icon.DOT
    }

    private fun kindColor(kind: IndexEventKind): Int = Widgets.rgbToU32(kindRgb(kind))

    private fun kindRgb(kind: IndexEventKind): Int = when (kind) {
        IndexEventKind.KILL, IndexEventKind.ATTACK, IndexEventKind.CRIT -> 0xFF453A
        IndexEventKind.DEATH -> 0xBF5AF2
        IndexEventKind.HURT -> 0xFF9F0A
        IndexEventKind.EXPLOSION -> 0xFF8A3D
        IndexEventKind.PROJECTILE_SPAWN, IndexEventKind.PROJECTILE_END -> 0x66D4CF
        IndexEventKind.TITLE, IndexEventKind.ACHIEVEMENT -> 0xFFD60A
        IndexEventKind.MARKER -> 0xFFFFFF
        IndexEventKind.JOIN, IndexEventKind.LEAVE, IndexEventKind.RESPAWN, IndexEventKind.DIMENSION -> 0x30D158
        else -> 0x8E8E93
    }

    private companion object {
        val ROW_HEIGHT: Float get() = EditorFonts.px(24f)
        val PRE_ROLL = Nanos.ofSeconds(8)
        val POST_ROLL = Nanos.ofSeconds(4)
        const val MAX_BATCH = 200
    }
}
