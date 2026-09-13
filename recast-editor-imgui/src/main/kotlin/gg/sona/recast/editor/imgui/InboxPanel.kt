package gg.sona.recast.editor.imgui

import gg.sona.recast.editor.EditorSession
import gg.sona.recast.editor.InboxEntry
import gg.sona.recast.editor.Selection
import imgui.ImGui

class InboxPanel(private val context: EditorContext) :
    AbstractPanel("Flashback", DockArea.BOTTOM, Icon.BOOKMARK, openByDefault = false) {

    override fun content(frame: FrameContext) {
        val session = context.session
        if (session == null) {
            Widgets.emptyState("No replay open", "Flashback suggestions appear here", Icon.WARNING)
            return
        }
        val entries = session.inbox()
        Widgets.header("Suggested clips")
        if (entries.isEmpty()) {
            Widgets.emptyState(
                "Inbox is empty",
                "Flashback watches your recording for kills, streaks and clutches and suggests clips here",
                Icon.CHECK
            )
            return
        }
        if (Widgets.accentButton("Approve all")) entries.forEach { approve(session, it) }
        ImGui.sameLine()
        if (Widgets.ghostButton("Dismiss all")) entries.forEach { session.reject(it.id) }
        ImGui.dummy(0f, 4f)
        for (entry in entries) card(session, entry)
    }

    private fun card(session: EditorSession, entry: InboxEntry) {
        ImGui.pushID(entry.id.toString())
        try {
            val request = entry.request
            val clip = entry.clip
            val list = ImGui.getWindowDrawList()
            val x = ImGui.getCursorScreenPosX()
            val y = ImGui.getCursorScreenPosY()
            val width = ImGui.getContentRegionAvailX()
            ImGui.beginGroup()
            ImGui.dummy(width, 6f)
            ImGui.indent(8f)
            EditorFonts.with(EditorFonts.bodyMedium) { ImGui.textUnformatted(request.trigger.title) }
            ImGui.sameLine()
            Widgets.pill(request.profile.name, EditorTheme.WARNING)
            Widgets.smallText(
                "${TimeFormat.clock(clip.startNanos)} - ${TimeFormat.clock(clip.endNanos)}    ${
                    TimeFormat.clock(
                        clip.durationNanos
                    )
                }    ${request.trigger.tags.joinToString(" ")}", EditorTheme.TEXT_MUTED.u32
            )
            if (Widgets.accentButton("Approve")) approve(session, entry)
            Widgets.tooltip("Add this clip to the project")
            ImGui.sameLine()
            if (Widgets.ghostButton("Preview")) {
                session.replay?.seek(clip.startNanos)
                session.replay?.play()
            }
            ImGui.sameLine()
            if (Widgets.ghostButton("Dismiss")) session.reject(entry.id)
            ImGui.unindent(8f)
            ImGui.dummy(width, 6f)
            ImGui.endGroup()
            val bottom = ImGui.getItemRectMaxY()
            list.addRectFilled(x, y, x + width, bottom, EditorTheme.PANEL_RAISED.u32, 4f)
            list.addRectFilled(x, y, x + 3f, bottom, EditorTheme.WARNING.u32, 2f)
            ImGui.dummy(0f, 4f)
        } finally {
            ImGui.popID()
        }
    }

    private fun approve(session: EditorSession, entry: InboxEntry) {
        val clip = session.approve(entry.id) ?: return
        context.clips.save(clip)
        session.selection = Selection(clipIds = setOf(clip.id))
        context.status("Added ${clip.title}")
    }
}

