package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.clip.ClipOrigin
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.EditorSession
import gg.sona.afterimage.editor.MarkerKind
import gg.sona.afterimage.editor.Selection
import gg.sona.afterimage.editor.TimelineMarker
import gg.sona.afterimage.editor.commands.AddClip
import gg.sona.afterimage.editor.commands.AddMarker
import gg.sona.afterimage.editor.commands.SetInOutPoints
import gg.sona.afterimage.index.IndexEventKind
import gg.sona.afterimage.index.query.ReplaySearch
import gg.sona.afterimage.index.query.SearchHit
import gg.sona.afterimage.index.query.SearchResult
import gg.sona.afterimage.replay.session.ReplaySession
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
    private var focusInput = false
    private var scrollTo = -1

    override fun content(frame: FrameContext) {
        val session = context.session
        val replay = session?.replay
        if (session == null || replay == null) {
            Widgets.emptyState("No replay open", "Search finds kills, fights, explosions and more", Icon.SEARCH)
            return
        }
        context.searchRequest?.let {
            pendingQuery = it
            focusInput = true
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
        if (focusInput) {
            ImGui.setKeyboardFocusHere()
            focusInput = false
        }
        val submitted = Widgets.search("##query", query, "Search the replay", flags = ImGuiInputTextFlags.EnterReturnsTrue)
        if (submitted && search != null) run(search)
        val current = result
        when {
            search == null -> indexing(events.progress)
            current == null || query.get().isBlank() -> examples(session)
            current.error != null -> error(current)
            else -> results(session, current)
        }
    }

    private fun run(search: ReplaySearch) {
        result = search.run(query.get())
        selected = -1
    }

    private fun indexing(progress: Double) {
        ImGui.dummy(0f, EditorFonts.px(6f))
        Widgets.progress(progress.toFloat(), -1f, "Indexing ${(progress * 100).toInt()}%")
        Widgets.wrappedText("Search is available once the recording is indexed.", EditorTheme.TEXT_DIM.u32)
    }

    private fun examples(session: EditorSession) {
        ImGui.dummy(0f, EditorFonts.px(4f))
        val names = session.events.index?.playerNames.orEmpty()
        if (names.isNotEmpty()) {
            Widgets.header("Players")
            val gap = EditorFonts.px(4f)
            var lineStart = true
            for (name in names.take(24)) {
                if (!lineStart) {
                    ImGui.sameLine(0f, gap)
                    if (ImGui.getContentRegionAvailX() < Widgets.chipWidth(name) + EditorFonts.px(2f)) ImGui.newLine()
                }
                lineStart = false
                if (Widgets.chipButton("player-$name", name, tooltipText = "Everything involving $name")) pendingQuery = "event player:$name"
            }
        }
        Widgets.header("Try")
        for ((index, example) in ReplaySearch.EXAMPLES.withIndex()) {
            val (text, description) = example
            if (Widgets.row("example-$index", EXAMPLE_HEIGHT, false) { x, y, width, hovered ->
                    val list = ImGui.getWindowDrawList()
                    val inset = EditorFonts.px(8f)
                    val top = y + (EXAMPLE_HEIGHT - ImGui.getFontSize() - EditorFonts.small.fontSize - EditorFonts.px(2f)) / 2f
                    list.addText(
                        EditorFonts.bodyMedium,
                        ImGui.getFontSize(),
                        x + inset,
                        top,
                        if (hovered) EditorTheme.TEXT.u32 else EditorTheme.ACCENT_TEXT.u32,
                        Widgets.clip(text, width - inset * 2f)
                    )
                    EditorFonts.with(EditorFonts.small) {
                        list.addText(
                            x + inset,
                            top + EditorFonts.body.fontSize + EditorFonts.px(2f),
                            EditorTheme.TEXT_DIM.u32,
                            Widgets.clip(description, width - inset * 2f)
                        )
                    }
                    if (hovered) Widgets.cursorHand()
                }
            ) pendingQuery = text
        }
        ImGui.dummy(0f, EditorFonts.px(4f))
        Widgets.wrappedText(
            "Filters: by:, on:, player:, near:, within:, min:, max:, after:, before:, text:. Conditions like health(me) < 8 find spans of time.",
            EditorTheme.TEXT_DIM.u32
        )
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
        ImGui.dummy(0f, EditorFonts.px(2f))
        val summary = when {
            current.hits.isEmpty() -> "No results"
            current.truncated -> "First ${current.hits.size} results"
            current.hits.size == 1 -> "1 result"
            else -> "${current.hits.size} results"
        }
        Widgets.chips(listOf(summary, "${current.elapsedMillis} ms"))
        if (current.hits.isNotEmpty()) {
            val size = ImGui.getFrameHeight()
            val gap = EditorFonts.px(4f)
            ImGui.sameLine(0f, EditorFonts.px(8f))
            Widgets.rightAlign(size * 2f + gap, spacing = 0f)
            if (Widgets.iconButton("results-markers", Icon.MARKER, size, "Add a marker at every result", iconScale = 0.55f)) markersFromResults(session, current)
            ImGui.sameLine(0f, gap)
            if (Widgets.iconButton("results-clips", Icon.FILM, size, "Add a clip around every result, ready for a montage", iconScale = 0.55f)) clipsFromResults(session, current)
        }
        if (current.hits.isEmpty()) {
            Widgets.emptyState("Nothing matched", "Try a wider filter or another player", Icon.SEARCH)
            return
        }
        ImGui.dummy(0f, EditorFonts.px(2f))
        keyboard(replay, current)
        if (ImGui.beginChild("results", 0f, 0f, false, ImGuiWindowFlags.None)) {
            for ((index, hit) in current.hits.withIndex()) {
                if (scrollTo == index) {
                    ImGui.setScrollHereY(0.5f)
                    scrollTo = -1
                }
                hitRow(session, replay.positionNanos, index, hit)
            }
        }
        ImGui.endChild()
    }

    private fun keyboard(replay: ReplaySession, current: SearchResult) {
        if (!ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows) || ImGui.getIO().wantTextInput) return
        val count = current.hits.size
        val step = when {
            ImGui.isKeyPressed(ImGuiKey.DownArrow, true) -> 1
            ImGui.isKeyPressed(ImGuiKey.UpArrow, true) -> -1
            else -> 0
        }
        if (step != 0 && count > 0) {
            selected = (selected + step).coerceIn(0, count - 1)
            scrollTo = selected
            replay.seek(current.hits[selected].nanos)
        }
        if (ImGui.isKeyPressed(ImGuiKey.Enter, false) && selected in 0 until count) replay.seek(current.hits[selected].nanos)
    }

    private fun hitRow(session: EditorSession, positionNanos: Long, index: Int, hit: SearchHit) {
        val replay = session.replay ?: return
        val atPlayhead = positionNanos in hit.nanos..maxOf(hit.nanos, hit.endNanos)
        val color = hit.kind?.let { kindColor(it) } ?: EditorTheme.TEXT_MUTED.u32
        ImGui.pushID(index)
        try {
            val clicked = Widgets.row("hit", ROW_HEIGHT, index == selected) { x, y, width, hovered ->
                val list = ImGui.getWindowDrawList()
                val inset = EditorFonts.px(8f)
                val iconSize = EditorFonts.px(14f)
                Icons.draw(list, kindIcon(hit.kind), x + inset, y + (ROW_HEIGHT - iconSize) / 2f, iconSize, color)
                val timeText = if (hit.isSpan) "${TimeFormat.short(hit.nanos)} to ${TimeFormat.short(hit.endNanos)}" else TimeFormat.clock(hit.nanos)
                val timeWidth = EditorFonts.with(EditorFonts.smallMedium) { Widgets.textWidth(timeText) }
                EditorFonts.with(EditorFonts.smallMedium) {
                    list.addText(
                        x + width - inset - timeWidth,
                        y + (ROW_HEIGHT - ImGui.getFontSize()) / 2f,
                        if (atPlayhead) EditorTheme.SELECTION.u32 else EditorTheme.TEXT_MUTED.u32,
                        timeText
                    )
                }
                val textX = x + inset + iconSize + EditorFonts.px(8f)
                val textWidth = width - (textX - x) - timeWidth - inset - EditorFonts.px(10f)
                val top = y + (ROW_HEIGHT - ImGui.getFontSize() - EditorFonts.small.fontSize - EditorFonts.px(2f)) / 2f
                list.addText(textX, top, EditorTheme.TEXT.u32, Widgets.clip(hit.label, textWidth))
                EditorFonts.with(EditorFonts.small) {
                    list.addText(
                        textX,
                        top + EditorFonts.body.fontSize + EditorFonts.px(2f),
                        EditorTheme.TEXT_DIM.u32,
                        Widgets.clip(hit.detail, textWidth)
                    )
                }
                if (hovered) Widgets.cursorHand()
            }
            if (clicked) {
                selected = index
                replay.seek(hit.nanos)
                context.status(hit.label)
            }
            if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left) && hit.hasPosition) {
                context.host.camera.frame(hit.x, hit.y, hit.z, 6.0)
            }
            if (Widgets.beginContextPopup("hit-menu")) {
                if (Menus.item("Go there")) replay.seek(hit.nanos)
                if (hit.hasPosition && Menus.item("Frame in scene")) context.host.camera.frame(hit.x, hit.y, hit.z, 6.0)
                ImGui.separator()
                if (Menus.item("Add marker")) addMarker(session, hit)
                if (Menus.item("Add clip around")) addClip(session, hit)
                if (Menus.item("Set in/out")) session.execute(
                    SetInOutPoints(
                        if (hit.isSpan) hit.nanos else maxOf(replay.startNanos, hit.nanos - PRE_ROLL),
                        if (hit.isSpan) hit.endNanos else minOf(replay.endNanos, hit.nanos + POST_ROLL)
                    )
                )
                Widgets.endPopup()
            }
        } finally {
            ImGui.popID()
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
        val ROW_HEIGHT: Float get() = EditorFonts.px(38f)
        val EXAMPLE_HEIGHT: Float get() = EditorFonts.px(36f)
        val PRE_ROLL = Nanos.ofSeconds(8)
        val POST_ROLL = Nanos.ofSeconds(4)
        const val MAX_BATCH = 200
    }
}
