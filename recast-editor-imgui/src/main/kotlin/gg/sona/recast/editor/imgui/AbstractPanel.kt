package gg.sona.recast.editor.imgui

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean

abstract class AbstractPanel(
    override val title: String,
    override val dockArea: DockArea,
    override val icon: Icon = Icon.LAYERS,
    openByDefault: Boolean = true,
    private val flags: Int = ImGuiWindowFlags.None,
) : EditorPanel {

    override val open: ImBoolean = ImBoolean(openByDefault)

    private val guard = PanelGuard(title)

    var hovered: Boolean = false
        private set

    var dockId: Int = 0
        private set

    override fun draw(frame: FrameContext) {
        if (!open.get()) return
        if (ImGui.begin(title, flags or ImGuiWindowFlags.NoCollapse)) {
            dockId = ImGui.getWindowDockID()
            hovered =
                ImGui.isWindowHovered(imgui.flag.ImGuiHoveredFlags.ChildWindows or imgui.flag.ImGuiHoveredFlags.AllowWhenBlockedByActiveItem)
            guard.run { content(frame) }
        }
        ImGui.end()
    }

    protected abstract fun content(frame: FrameContext)
}
