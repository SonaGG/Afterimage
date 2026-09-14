package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.EditorSession
import gg.sona.afterimage.editor.Segment
import imgui.ImGui
import imgui.flag.ImGuiTableColumnFlags
import imgui.flag.ImGuiTableFlags
import java.nio.file.Path

class SequencePanel(private val context: EditorContext) :
    DialogPanel("Sequence", Icon.LAYERS, 760f, 460f, hasFooter = true) {

    private var addPath: Path? = null
    private var recordings: List<Path> = emptyList()
    private var lastListNanos = 0L

    override fun content(frame: FrameContext) {
        val session = context.session
        if (session == null) {
            Widgets.emptyState("No project open", "Open a project or recording first", Icon.FILM)
            return
        }
        val project = session.project
        if (frame.nowNanos - lastListNanos > Nanos.ofSeconds(3)) {
            lastListNanos = frame.nowNanos
            recordings = context.host.replay.listRecordings()
        }
        Widgets.smallText(
            "Gameplay segments play back to back. Trim each one with in and out, then rebuild.",
            EditorTheme.TEXT_DIM.u32
        )
        ImGui.dummy(0f, EditorFonts.px(4f))
        val flags = ImGuiTableFlags.RowBg or ImGuiTableFlags.BordersInnerH or ImGuiTableFlags.SizingStretchProp
        if (ImGui.beginTable("segments", 4, flags)) {
            ImGui.tableSetupColumn("#", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(22f))
            ImGui.tableSetupColumn("Gameplay", ImGuiTableColumnFlags.WidthStretch)
            ImGui.tableSetupColumn("Range", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(200f))
            ImGui.tableSetupColumn("", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(92f))
            var move: Pair<Int, Int>? = null
            var remove = -1
            for ((index, segment) in project.segments.withIndex()) {
                ImGui.pushID(index)
                try {
                    ImGui.tableNextRow()
                    ImGui.tableNextColumn()
                    ImGui.alignTextToFramePadding()
                    Widgets.mutedText("${index + 1}")
                    ImGui.tableNextColumn()
                    ImGui.alignTextToFramePadding()
                    ImGui.textUnformatted(segment.gameplay.fileName.toString().substringBeforeLast('.'))
                    val length = segment.lengthNanos.takeIf { it > 0L }
                        ?: context.host.replay.describe(segment.gameplay)?.durationNanos ?: 0L
                    ImGui.sameLine()
                    Widgets.smallText(TimeFormat.clock(length), EditorTheme.TEXT_DIM.u32)
                    ImGui.tableNextColumn()
                    val inSeconds = FloatArray(1) { (segment.inNanos / Nanos.PER_SECOND.toDouble()).toFloat() }
                    val outSeconds = FloatArray(1) { (segment.outNanos / Nanos.PER_SECOND.toDouble()).toFloat() }
                    ImGui.setNextItemWidth(EditorFonts.px(92f))
                    if (ImGui.dragFloat("##in", inSeconds, 0.5f, 0f, 100000f, "in %.1f s")) update(
                        session,
                        index,
                        segment.copy(inNanos = (inSeconds[0] * Nanos.PER_SECOND).toLong().coerceAtLeast(0L))
                    )
                    ImGui.sameLine()
                    ImGui.setNextItemWidth(EditorFonts.px(98f))
                    if (ImGui.dragFloat(
                            "##out",
                            outSeconds,
                            0.5f,
                            0f,
                            100000f,
                            if (segment.outNanos == 0L) "out: end" else "out %.1f s"
                        )
                    ) update(
                        session,
                        index,
                        segment.copy(outNanos = (outSeconds[0] * Nanos.PER_SECOND).toLong().coerceAtLeast(0L))
                    )
                    Widgets.tooltip("Drag to trim. Out 0 means until the end of the recording.")
                    ImGui.tableNextColumn()
                    if (Widgets.iconButton("up", Icon.CHEVRON_UP, EditorFonts.px(22f), "Move up") && index > 0) move =
                        index to index - 1
                    ImGui.sameLine()
                    if (Widgets.iconButton(
                            "down",
                            Icon.CHEVRON_DOWN,
                            EditorFonts.px(22f),
                            "Move down"
                        ) && index < project.segments.size - 1
                    ) move = index to index + 1
                    ImGui.sameLine()
                    if (Widgets.iconButton(
                            "remove",
                            Icon.TRASH,
                            EditorFonts.px(22f),
                            "Remove from the sequence",
                            color = EditorTheme.RECORD.u32
                        )
                    ) remove = index
                } finally {
                    ImGui.popID()
                }
            }
            ImGui.endTable()
            move?.let { (from, to) ->
                val item = project.segments.removeAt(from)
                project.segments.add(to, item)
                project.dirty = true
            }
            if (remove >= 0 && project.segments.size > 1) {
                project.segments.removeAt(remove)
                project.dirty = true
            }
        }
        ImGui.dummy(0f, EditorFonts.px(6f))
        Widgets.header("Add gameplay")
        if (Widgets.popupButton(
                "add",
                addPath?.fileName?.toString()?.substringBeforeLast('.') ?: "Pick a recording",
                EditorFonts.px(320f),
                muted = addPath == null
            )
        ) {
            for (path in recordings) {
                if (Menus.item(path.fileName.toString().substringBeforeLast('.'), "", addPath == path)) addPath =
                    path
            }
            Widgets.endPopup()
        }
        ImGui.sameLine()
        val chosen = addPath
        if (chosen == null) ImGui.beginDisabled()
        if (Widgets.button("Add")) {
            project.segments += Segment(chosen!!, 0L, 0L, context.host.replay.describe(chosen)?.durationNanos ?: 0L)
            project.dirty = true
            addPath = null
        }
        if (chosen == null) ImGui.endDisabled()
        Widgets.smallText(
            "Tip: set in/out on the timeline, then use Edit > Trim segment to current in/out.",
            EditorTheme.TEXT_DIM.u32
        )
    }

    override fun footer(frame: FrameContext) {
        val session = context.session ?: return
        val building = context.host.replay.sequenceBuilding()
        ImGui.alignTextToFramePadding()
        if (session.project.sequenceStale) Widgets.pill("changed    rebuild to apply", EditorTheme.WARNING)
        else Widgets.smallText("${session.project.segments.size} segments", EditorTheme.TEXT_DIM.u32)
        ImGui.sameLine()
        val label = if (building) "Building..." else "Rebuild sequence"
        Widgets.rightAlign(Widgets.buttonWidth(label, Widgets.ButtonStyle.ACCENT))
        if (building) ImGui.beginDisabled()
        if (Widgets.accentButton(label)) {
            if (!context.host.replay.rebuildSequence()) context.status("Nothing to rebuild: add a second segment or trim one first")
        }
        if (building) ImGui.endDisabled()
        Widgets.tooltip("Stitches the segments into one continuous timeline and reopens the project. Camera keyframes keep their times, so rebuild before you animate.")
    }

    private fun update(session: EditorSession, index: Int, segment: Segment) {
        session.project.segments[index] = segment
        session.project.dirty = true
    }
}
