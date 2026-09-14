package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.camera.track.SegmentMode
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiStyleVar

object EditorTheme {
    class Rgb(val hex: Int, val alpha: Float = 1f) {
        val r: Float get() = ((hex shr 16) and 0xFF) / 255f
        val g: Float get() = ((hex shr 8) and 0xFF) / 255f
        val b: Float get() = (hex and 0xFF) / 255f
        val u32: Int get() = ImGui.getColorU32(r, g, b, alpha)

        fun alpha(value: Float): Rgb = Rgb(hex, value)

        fun u32(value: Float): Int = ImGui.getColorU32(r, g, b, value)

        fun abgr(value: Float = alpha): Int = ((value * 255f).toInt()
            .coerceIn(0, 255) shl 24) or ((hex and 0xFF) shl 16) or (hex and 0xFF00) or ((hex shr 16) and 0xFF)
    }

    val APP_BG = Rgb(0x1C1C1E)
    val PANEL = Rgb(0x252528)
    val PANEL_RAISED = Rgb(0x2E2E31)
    val PANEL_SUNKEN = Rgb(0x171719)
    val HEADER = Rgb(0x28282B)
    val BORDER = Rgb(0x000000, 0.35f)
    val BORDER_SOFT = Rgb(0xFFFFFF, 0.09f)
    val SEPARATOR = Rgb(0x353538)
    val GROUP = Rgb(0x2C2C2F)
    val CONTROL = Rgb(0x3A3A3D)
    val CONTROL_HOVER = Rgb(0x48484B)
    val CONTROL_ACTIVE = Rgb(0x565659)
    val FIELD = Rgb(0x1A1A1C)
    val TEXT = Rgb(0xF2F2F7)
    val TEXT_MUTED = Rgb(0x9E9EA4)
    val TEXT_DIM = Rgb(0x707076)
    val ACCENT = Rgb(0x8E8E93)
    val ACCENT_HOVER = Rgb(0x9E9EA3)
    val ACCENT_ACTIVE = Rgb(0x7C7C81)
    val ACCENT_TEXT = Rgb(0xE5E5EA)
    val PRIMARY = Rgb(0xE9E9EE)
    val PRIMARY_HOVER = Rgb(0xF7F7FA)
    val PRIMARY_ACTIVE = Rgb(0xCFCFD4)
    val PRIMARY_TEXT = Rgb(0x1C1C1E)
    val TIMECODE = Rgb(0xF2F2F7)
    val SWITCH_ON = Rgb(0x34C759)
    val SWITCH_ON_HOVER = Rgb(0x3FD466)
    val SWITCH_OFF = Rgb(0x48484B)
    val SWITCH_OFF_HOVER = Rgb(0x545457)
    val SWITCH_KNOB = Rgb(0xFFFFFF)
    val RECORD = Rgb(0xFF453A)
    val WARNING = Rgb(0xFF9F0A)
    val SUCCESS = Rgb(0x30D158)
    val SELECTION = Rgb(0xF5C542)
    val SELECTION_FILL = Rgb(0xFFFFFF, 0.13f)
    val PURPLE = Rgb(0xBF5AF2)
    val MINT = Rgb(0x66D4CF)
    val PINK = Rgb(0xFF375F)

    val AXIS_X = Rgb(0xFF453A)
    val AXIS_Y = Rgb(0x30D158)
    val AXIS_Z = Rgb(0x3E8BFF)

    val RULER_BG = Rgb(0x1E1E20)
    val LANE_A = Rgb(0x202022)
    val LANE_B = Rgb(0x232326)
    val LANE_HEADER = Rgb(0x252528)
    val LANE_LINE = Rgb(0x000000, 0.4f)
    val PLAYHEAD = Rgb(0xF2F2F2)
    val SKIMMER = Rgb(0xFF453A)
    val IN_OUT = Rgb(0xB4B4B8)
    val WORK_AREA = Rgb(0xFFFFFF, 0.035f)
    val CLIP = Rgb(0x4A4A52)
    val CLIP_HOVER = Rgb(0x56565E)
    val CLIP_SELECTED = Rgb(0x62626B)
    val KEYFRAME_SMOOTH = Rgb(0xF2F2F2)
    val KEYFRAME_LINEAR = Rgb(0xA6A6AB)
    val KEYFRAME_BEZIER = Rgb(0xBF5AF2)
    val KEYFRAME_HOLD = Rgb(0xFF9F0A)
    val MARKER = Rgb(0x30D158)
    val EVENT_KILL = Rgb(0xFF453A)
    val EVENT_HIT = Rgb(0xFF9F0A)
    val EVENT_OTHER = Rgb(0xA6A6AB)

    fun apply() {
        val style = ImGui.getStyle()

        style.setWindowPadding(10f, 8f)
        style.setFramePadding(8f, 4f)
        style.setCellPadding(6f, 3f)
        style.setItemSpacing(6f, 6f)
        style.setItemInnerSpacing(6f, 4f)
        style.indentSpacing = 16f
        style.scrollbarSize = 9f
        style.grabMinSize = 10f
        style.windowBorderSize = 0f
        style.childBorderSize = 0f
        style.frameBorderSize = 0f
        style.popupBorderSize = 1f
        style.tabBorderSize = 0f
        style.windowRounding = 0f
        style.childRounding = 5f
        style.frameRounding = 5f
        style.popupRounding = 7f
        style.scrollbarRounding = 9f
        style.grabRounding = 5f
        style.tabRounding = 4f
        style.dockingSeparatorSize = 1f
        style.setWindowTitleAlign(0f, 0.5f)
        style.windowMenuButtonPosition = -1
        style.setSelectableTextAlign(0f, 0.5f)
        style.separatorTextBorderSize = 1f
        style.setWindowMinSize(32f, 24f)

        set(ImGuiCol.Text, TEXT)
        set(ImGuiCol.TextDisabled, TEXT_DIM)
        set(ImGuiCol.WindowBg, PANEL)
        set(ImGuiCol.ChildBg, Rgb(0x000000, 0f))
        set(ImGuiCol.PopupBg, PANEL_RAISED)
        set(ImGuiCol.Border, BORDER_SOFT)
        set(ImGuiCol.BorderShadow, Rgb(0x000000, 0f))
        set(ImGuiCol.FrameBg, FIELD)
        set(ImGuiCol.FrameBgHovered, Rgb(0x000000, 0.36f))
        set(ImGuiCol.FrameBgActive, Rgb(0x000000, 0.42f))
        set(ImGuiCol.TitleBg, APP_BG)
        set(ImGuiCol.TitleBgActive, APP_BG)
        set(ImGuiCol.TitleBgCollapsed, APP_BG)
        set(ImGuiCol.MenuBarBg, APP_BG)
        set(ImGuiCol.ScrollbarBg, Rgb(0x000000, 0f))
        set(ImGuiCol.ScrollbarGrab, Rgb(0xFFFFFF, 0.14f))
        set(ImGuiCol.ScrollbarGrabHovered, Rgb(0xFFFFFF, 0.22f))
        set(ImGuiCol.ScrollbarGrabActive, Rgb(0xFFFFFF, 0.3f))
        set(ImGuiCol.CheckMark, ACCENT_TEXT)
        set(ImGuiCol.SliderGrab, Rgb(0xFFFFFF, 0.9f))
        set(ImGuiCol.SliderGrabActive, Rgb(0xFFFFFF, 1f))
        set(ImGuiCol.Button, CONTROL)
        set(ImGuiCol.ButtonHovered, CONTROL_HOVER)
        set(ImGuiCol.ButtonActive, CONTROL_ACTIVE)
        set(ImGuiCol.Header, Rgb(0xFFFFFF, 0.06f))
        set(ImGuiCol.HeaderHovered, Rgb(0xFFFFFF, 0.09f))
        set(ImGuiCol.HeaderActive, SELECTION_FILL)
        set(ImGuiCol.Separator, SEPARATOR)
        set(ImGuiCol.SeparatorHovered, Rgb(0xFFFFFF, 0.25f))
        set(ImGuiCol.SeparatorActive, Rgb(0xFFFFFF, 0.4f))
        set(ImGuiCol.ResizeGrip, Rgb(0x000000, 0f))
        set(ImGuiCol.ResizeGripHovered, Rgb(0xFFFFFF, 0.2f))
        set(ImGuiCol.ResizeGripActive, Rgb(0xFFFFFF, 0.35f))
        set(ImGuiCol.Tab, APP_BG)
        set(ImGuiCol.TabHovered, PANEL_RAISED)
        set(ImGuiCol.TabActive, PANEL)
        set(ImGuiCol.TabUnfocused, APP_BG)
        set(ImGuiCol.TabUnfocusedActive, PANEL)
        set(ImGuiCol.DockingPreview, Rgb(0xFFFFFF, 0.25f))
        set(ImGuiCol.DockingEmptyBg, APP_BG)
        set(ImGuiCol.PlotLines, ACCENT_TEXT)
        set(ImGuiCol.PlotLinesHovered, TEXT)
        set(ImGuiCol.PlotHistogram, ACCENT)
        set(ImGuiCol.PlotHistogramHovered, ACCENT_HOVER)
        set(ImGuiCol.TableHeaderBg, HEADER)
        set(ImGuiCol.TableBorderStrong, SEPARATOR)
        set(ImGuiCol.TableBorderLight, SEPARATOR)
        set(ImGuiCol.TableRowBg, Rgb(0x000000, 0f))
        set(ImGuiCol.TableRowBgAlt, Rgb(0xFFFFFF, 0.02f))
        set(ImGuiCol.TextSelectedBg, Rgb(0xFFFFFF, 0.22f))
        set(ImGuiCol.DragDropTarget, SELECTION)
        set(ImGuiCol.NavHighlight, Rgb(0x000000, 0f))
        set(ImGuiCol.ModalWindowDimBg, Rgb(0x000000, 0.55f))
    }

    fun pushCompactFrame() = ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 6f, 2f)

    fun pushToolbarStyle() {
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 6f, 3f)
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 4f, 4f)
    }

    fun popToolbarStyle() = ImGui.popStyleVar(2)

    fun modeColor(mode: SegmentMode): Rgb = when (mode) {
        SegmentMode.CATMULL_ROM -> KEYFRAME_SMOOTH
        SegmentMode.LINEAR -> KEYFRAME_LINEAR
        SegmentMode.BEZIER -> KEYFRAME_BEZIER
        SegmentMode.HOLD -> KEYFRAME_HOLD
    }

    private fun set(target: Int, colour: Rgb) =
        ImGui.getStyle().setColor(target, colour.r, colour.g, colour.b, colour.alpha)
}
