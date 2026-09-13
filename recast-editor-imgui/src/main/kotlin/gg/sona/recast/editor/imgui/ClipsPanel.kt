package gg.sona.recast.editor.imgui

import gg.sona.recast.clip.Clip
import gg.sona.recast.clip.ClipOrigin
import gg.sona.recast.core.time.Nanos
import gg.sona.recast.editor.EditorSession
import gg.sona.recast.editor.Selection
import gg.sona.recast.editor.commands.AddClip
import gg.sona.recast.editor.commands.RemoveClip
import gg.sona.recast.editor.commands.SetInOutPoints
import imgui.ImGui
import imgui.flag.ImGuiSelectableFlags
import imgui.flag.ImGuiTableColumnFlags
import imgui.flag.ImGuiTableFlags
import imgui.type.ImString
import java.util.*

class ClipsPanel(private val context: EditorContext) : AbstractPanel("Clips", DockArea.BOTTOM, Icon.FILM) {

    private val filter = ImString("", 64)
    private var preRollSeconds = 15
    private var postRollSeconds = 5
    private var confirmDelete: UUID? = null

    override fun content(frame: FrameContext) {
        val session = context.session
        val replay = session?.replay
        if (session == null || replay == null) {
            Widgets.emptyState("No replay open", "Clips are ranges of a recording you want to keep", Icon.FILM)
            return
        }
        val project = session.project
        EditorTheme.pushToolbarStyle()
        try {
            val inPoint = project.inPointNanos
            val outPoint = if (project.outPointNanos > 0L) project.outPointNanos else replay.durationNanos
            val canCreate = outPoint - inPoint >= Nanos.PER_TICK
            if (!canCreate) ImGui.beginDisabled()
            if (Widgets.accentButton("Clip from in/out")) add(
                session,
                Clip(
                    UUID.randomUUID(),
                    project.recording,
                    project.sessionId,
                    inPoint,
                    outPoint,
                    "Clip ${project.clips.size + 1}"
                )
            )
            if (!canCreate) ImGui.endDisabled()
            Widgets.tooltip(
                if (canCreate) "Create a clip covering ${TimeFormat.clock(inPoint)} - ${
                    TimeFormat.clock(
                        outPoint
                    )
                }" else "Set in and out points first (I / O)"
            )
            ImGui.sameLine()
            if (Widgets.ghostButton("Around playhead")) {
                add(
                    session,
                    Clip.around(
                        project.recording,
                        project.sessionId,
                        replay.positionNanos,
                        Nanos.ofSeconds(preRollSeconds.toLong()),
                        Nanos.ofSeconds(postRollSeconds.toLong()),
                        "Moment ${project.clips.size + 1}",
                        ClipOrigin.MANUAL
                    )
                )
            }
            Widgets.tooltip("Create a clip from $preRollSeconds s before to $postRollSeconds s after the playhead")
            ImGui.sameLine()
            ImGui.setNextItemWidth(64f)
            Widgets.intDrag("##pre", preRollSeconds, 0.2f, 0, 600, "-%d s")?.let { preRollSeconds = it }
            ImGui.sameLine()
            ImGui.setNextItemWidth(64f)
            Widgets.intDrag("##post", postRollSeconds, 0.2f, 0, 600, "+%d s")?.let { postRollSeconds = it }
        } finally {
            EditorTheme.popToolbarStyle()
        }
        Widgets.search("##filter", filter, "Search clips")

        val query = filter.get().lowercase()
        val visible = project.clips.filter {
            query.isEmpty() || it.title.lowercase().contains(query) || it.tags.any { tag ->
                tag.lowercase().contains(query)
            }
        }
        if (project.clips.isEmpty()) {
            Widgets.emptyState(
                "No clips yet",
                "Set in/out points and press 'Clip from in/out', or approve Flashback suggestions",
                Icon.FILM
            )
            return
        }
        val flags =
            ImGuiTableFlags.RowBg or ImGuiTableFlags.ScrollY or ImGuiTableFlags.Resizable or ImGuiTableFlags.BordersInnerV
        if (ImGui.beginTable("clips", 5, flags)) {
            ImGui.tableSetupScrollFreeze(0, 1)
            ImGui.tableSetupColumn("Title", ImGuiTableColumnFlags.WidthStretch)
            ImGui.tableSetupColumn("In", ImGuiTableColumnFlags.WidthFixed, 72f)
            ImGui.tableSetupColumn("Length", ImGuiTableColumnFlags.WidthFixed, 64f)
            ImGui.tableSetupColumn("Origin", ImGuiTableColumnFlags.WidthFixed, 74f)
            ImGui.tableSetupColumn("##actions", ImGuiTableColumnFlags.WidthFixed, 112f)
            ImGui.tableHeadersRow()
            for (clip in visible) {
                ImGui.pushID(clip.id.toString())
                try {
                    ImGui.tableNextRow()
                    ImGui.tableNextColumn()
                    val selected = clip.id in session.selection.clipIds
                    ImGui.setNextItemAllowOverlap()
                    if (ImGui.selectable(
                            "##row",
                            selected,
                            ImGuiSelectableFlags.SpanAllColumns or ImGuiSelectableFlags.AllowItemOverlap or ImGuiSelectableFlags.AllowDoubleClick,
                            0f,
                            ROW_HEIGHT
                        )
                    ) {
                        session.selection = session.selection.withClip(clip.id, ImGui.getIO().keyCtrl)
                        if (ImGui.isMouseDoubleClicked(0)) replay.seek(clip.startNanos)
                    }
                    if (ImGui.beginPopupContextItem("clip-menu")) {
                        if (ImGui.menuItem("Play")) ClipActions.play(session, clip)
                        if (ImGui.menuItem("Go to start")) replay.seek(clip.startNanos)
                        if (ImGui.menuItem("Set in/out to clip")) session.execute(
                            SetInOutPoints(
                                clip.startNanos,
                                clip.endNanos
                            )
                        )
                        ImGui.separator()
                        if (ImGui.menuItem("Bake to file")) ClipActions.bake(context, clip)
                        if (ImGui.menuItem("Export video")) ClipActions.export(context, clip)
                        ImGui.separator()
                        if (ImGui.menuItem("Delete")) confirmDelete = clip.id
                        ImGui.endPopup()
                    }
                    ImGui.sameLine()
                    ImGui.setCursorPosX(ImGui.getCursorPosX() - ImGui.getStyle().itemSpacingX)
                    ImGui.alignTextToFramePadding()
                    ImGui.textUnformatted(clip.title)
                    if (clip.tags.isNotEmpty()) {
                        ImGui.sameLine()
                        Widgets.smallText(clip.tags.joinToString(" "), EditorTheme.TEXT_DIM.u32)
                    }
                    ImGui.tableNextColumn()
                    ImGui.alignTextToFramePadding()
                    Widgets.mutedText(TimeFormat.clock(clip.startNanos))
                    ImGui.tableNextColumn()
                    ImGui.alignTextToFramePadding()
                    Widgets.mutedText(TimeFormat.clock(clip.durationNanos))
                    ImGui.tableNextColumn()
                    Widgets.pill(
                        clip.origin.name.lowercase(),
                        if (clip.origin == ClipOrigin.FLASHBACK) EditorTheme.WARNING else EditorTheme.CONTROL_ACTIVE
                    )
                    ImGui.tableNextColumn()
                    if (Widgets.iconButton("play", Icon.PLAY, ACTION, "Play clip (sets in/out)")) ClipActions.play(
                        session,
                        clip
                    )
                    ImGui.sameLine()
                    if (Widgets.iconButton(
                            "bake",
                            Icon.FILM,
                            ACTION,
                            "Bake to a standalone .recast file"
                        )
                    ) ClipActions.bake(context, clip)
                    ImGui.sameLine()
                    if (Widgets.iconButton("export", Icon.EXPORT, ACTION, "Export video")) ClipActions.export(
                        context,
                        clip
                    )
                    ImGui.sameLine()
                    if (Widgets.iconButton(
                            "delete",
                            Icon.TRASH,
                            ACTION,
                            "Delete clip",
                            color = EditorTheme.RECORD.u32
                        )
                    ) confirmDelete = clip.id
                } finally {
                    ImGui.popID()
                }
            }
            ImGui.endTable()
        }
        if (visible.size != project.clips.size) Widgets.smallText(
            "${project.clips.size - visible.size} hidden by search",
            EditorTheme.TEXT_DIM.u32
        )
        deleteDialog(session)
    }

    private fun deleteDialog(session: EditorSession) {
        val id = confirmDelete ?: return
        val clip = session.project.clip(id)
        if (clip == null) {
            confirmDelete = null
            return
        }
        Dialog.confirm(
            "Delete clip?",
            "Delete \"${clip.title}\"? The recording itself is not affected.",
            "Delete",
            onConfirm = {
                session.execute(RemoveClip(clip.id))
                context.clips.delete(clip.id)
                if (clip.id in session.selection.clipIds) session.selection = Selection.NONE
                confirmDelete = null
            },
            onCancel = { confirmDelete = null })
    }

    private fun add(session: EditorSession, clip: Clip) {
        session.execute(AddClip(clip))
        context.clips.save(clip)
        session.selection = Selection(clipIds = setOf(clip.id))
        context.status("Added ${clip.title}")
    }

    private companion object {
        const val ROW_HEIGHT = 24f
        const val ACTION = 22f
    }
}
