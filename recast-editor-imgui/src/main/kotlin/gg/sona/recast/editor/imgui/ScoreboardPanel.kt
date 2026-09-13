package gg.sona.recast.editor.imgui

import imgui.ImGui
import imgui.flag.ImGuiTableColumnFlags
import imgui.flag.ImGuiTableFlags
import imgui.flag.ImGuiWindowFlags

class ScoreboardPanel(private val context: EditorContext) : DialogPanel("Scoreboard", Icon.TAG, 760f, 500f) {

    override fun content(frame: FrameContext) {
        val replay = context.replay
        if (replay == null) {
            Widgets.emptyState("No replay open", "Server scoreboards and the tab list show up here", Icon.INFO)
            return
        }
        val scoreboard = replay.shadow.scoreboard
        val sidebarWidth = EditorFonts.px(250f)
        val height = ImGui.getContentRegionAvailY()
        if (ImGui.beginChild("##sidebar-objective", sidebarWidth, height, false, ImGuiWindowFlags.None)) {
            Widgets.header("Sidebar")
            val sidebar = scoreboard.displaySlots.getOrNull(SIDEBAR)?.let { scoreboard.objectives[it] }
            if (sidebar == null) {
                Widgets.smallText("No sidebar objective is displayed right now.", EditorTheme.TEXT_DIM.u32)
            } else {
                val x = ImGui.getCursorScreenPosX()
                val y = ImGui.getCursorScreenPosY()
                val width = ImGui.getContentRegionAvailX()
                val list = ImGui.getWindowDrawList()
                ImGui.beginGroup()
                ImGui.dummy(width, EditorFonts.px(6f))
                ImGui.indent(EditorFonts.px(10f))
                EditorFonts.with(EditorFonts.bodyMedium) { ImGui.textUnformatted(strip(sidebar.displayName)) }
                val rows = sidebar.scores.entries.sortedByDescending { it.value }.take(15)
                for ((name, score) in rows) {
                    val team = scoreboard.teams.values.firstOrNull { name in it.members }
                    ImGui.textUnformatted(strip((team?.prefix ?: "") + name + (team?.suffix ?: "")))
                    ImGui.sameLine(width - EditorFonts.px(40f))
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, EditorTheme.RECORD.u32)
                    ImGui.textUnformatted(score.toString())
                    ImGui.popStyleColor()
                }
                ImGui.unindent(EditorFonts.px(10f))
                ImGui.dummy(width, EditorFonts.px(6f))
                ImGui.endGroup()
                list.addRectFilled(x, y, x + width, ImGui.getItemRectMaxY(), EditorTheme.GROUP.u32, EditorFonts.px(8f))
            }
            if (scoreboard.teams.isNotEmpty()) {
                ImGui.dummy(0f, EditorFonts.px(4f))
                Widgets.smallText(
                    "${scoreboard.teams.size} teams    ${scoreboard.objectives.size} objectives",
                    EditorTheme.TEXT_DIM.u32
                )
            }
        }
        ImGui.endChild()
        ImGui.sameLine(0f, EditorFonts.px(20f))
        if (ImGui.beginChild("##players", 0f, height, false, ImGuiWindowFlags.None)) {
            Widgets.header("Players")
            val players = replay.shadow.players.entries.values.sortedBy { it.name.lowercase() }
            val tabObjective = scoreboard.displaySlots.getOrNull(TAB_LIST)?.let { scoreboard.objectives[it] }
            if (ImGui.beginTable(
                    "tab",
                    4,
                    ImGuiTableFlags.RowBg or ImGuiTableFlags.ScrollY or ImGuiTableFlags.BordersInnerH
                )
            ) {
                ImGui.tableSetupScrollFreeze(0, 1)
                ImGui.tableSetupColumn("Name", ImGuiTableColumnFlags.WidthStretch)
                ImGui.tableSetupColumn("Mode", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(80f))
                ImGui.tableSetupColumn("Ping", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(52f))
                ImGui.tableSetupColumn(tabObjective?.let { strip(it.displayName) } ?: "Score",
                    ImGuiTableColumnFlags.WidthFixed,
                    EditorFonts.px(64f))
                ImGui.tableHeadersRow()
                for (entry in players) {
                    ImGui.tableNextRow()
                    ImGui.tableNextColumn()
                    val team = scoreboard.teams.values.firstOrNull { entry.name in it.members }
                    ImGui.textUnformatted(strip((team?.prefix ?: "") + entry.name + (team?.suffix ?: "")))
                    ImGui.tableNextColumn()
                    Widgets.mutedText(GAME_MODES.getOrElse(entry.gameMode) { "?" })
                    ImGui.tableNextColumn()
                    Widgets.mutedText("${entry.ping}")
                    ImGui.tableNextColumn()
                    Widgets.mutedText(tabObjective?.scores?.get(entry.name)?.toString() ?: "")
                }
                ImGui.endTable()
            }
        }
        ImGui.endChild()
    }

    private fun strip(text: String): String = text.replace(Regex("§."), "")

    private companion object {
        const val SIDEBAR = 1
        const val TAB_LIST = 0
        val GAME_MODES = listOf("survival", "creative", "adventure", "spectator")
    }
}
