package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.camera.EaseDirection
import gg.sona.afterimage.camera.Easing
import gg.sona.afterimage.camera.EasingFamily
import gg.sona.afterimage.camera.EasingKind
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiMouseCursor
import kotlin.math.abs
import kotlin.math.roundToInt

object EasingWidgets {
    private var activeHandle: String? = null
    private var pickerDirection = EaseDirection.IN_OUT

    fun curve(
        list: ImDrawList,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        easing: Easing,
        color: Int,
        thickness: Float = 1.5f,
        samples: Int = 40,
        overshoot: Float = 0.25f,
    ) {
        val span = 1f + overshoot * 2f
        fun px(t: Double): Float = x + width * t.toFloat()
        fun py(v: Double): Float = y + height * (1f - (v.toFloat() + overshoot) / span)
        if (easing.isHold) {
            list.addLine(px(0.0), py(0.0), px(1.0), py(0.0), color, thickness)
            list.addLine(px(1.0), py(0.0), px(1.0), py(1.0), color, thickness)
            return
        }
        var previousX = px(0.0)
        var previousY = py(0.0)
        for (index in 1..samples) {
            val t = index.toDouble() / samples
            val v = easing.apply(t)
            val cx = px(t)
            val cy = py(v)
            list.addLine(previousX, previousY, cx, cy, color, thickness)
            previousX = cx
            previousY = cy
        }
    }

    fun picker(id: String, current: Easing, width: Float = -1f): Easing? {
        val height = ImGui.getFrameHeight()
        val actual = if (width > 0f) width else ImGui.getContentRegionAvailX()
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val pressed = ImGui.invisibleButton(id, maxOf(1f, actual), height)
        val hovered = ImGui.isItemHovered()
        val list = ImGui.getWindowDrawList()
        list.addRectFilled(
            x, y, x + actual, y + height,
            if (hovered) EditorTheme.CONTROL_HOVER.u32 else EditorTheme.CONTROL.u32,
            EditorFonts.px(5f)
        )
        val thumb = height - EditorFonts.px(8f)
        curve(
            list,
            x + EditorFonts.px(8f),
            y + EditorFonts.px(4f),
            thumb,
            thumb,
            current,
            EditorTheme.ACCENT_TEXT.u32,
            1.5f,
            16
        )
        list.addText(
            x + EditorFonts.px(14f) + thumb,
            y + (height - ImGui.getFontSize()) / 2f,
            EditorTheme.TEXT.u32,
            Widgets.clip(current.label, actual - thumb - EditorFonts.px(36f))
        )
        Icons.draw(
            list, Icon.CHEVRON_DOWN,
            x + actual - EditorFonts.px(17f), y + (height - EditorFonts.px(9f)) / 2f,
            EditorFonts.px(9f), EditorTheme.TEXT_DIM.u32
        )
        if (pressed) ImGui.openPopup("$id-menu")
        var result: Easing? = null
        if (Widgets.beginPopup("$id-menu")) {
            result = grid(current)
            if (result != null) ImGui.closeCurrentPopup()
            Widgets.endPopup()
        }
        return result
    }

    fun grid(current: Easing): Easing? {
        var result: Easing? = null
        val cell = EditorFonts.px(50f)
        val gap = EditorFonts.px(4f)
        val columns = 5
        val total = cell * columns + gap * (columns - 1)
        if (ImGui.isWindowAppearing()) pickerDirection =
            current.kind.direction.takeIf { it != EaseDirection.NONE } ?: EaseDirection.IN_OUT
        val directions = listOf(EaseDirection.IN, EaseDirection.OUT, EaseDirection.IN_OUT)
        Widgets.segmented(
            "ease-direction",
            listOf("In", "Out", "In out"),
            directions.indexOf(pickerDirection),
            (total - EditorFonts.px(8f)) / 3f,
            listOf("Slow start", "Slow end", "Slow start and end")
        )?.let { pickerDirection = directions[it] }
        ImGui.dummy(0f, EditorFonts.px(2f))
        val ease = EasingKind.of(EasingFamily.EASE, pickerDirection) ?: EasingKind.EASE
        val families = EasingFamily.entries.filter {
            it != EasingFamily.LINEAR && it != EasingFamily.EASE && it != EasingFamily.HOLD && it != EasingFamily.CUSTOM
        }
        val kinds = listOf(EasingKind.LINEAR, ease, EasingKind.HOLD) + families.mapNotNull { EasingKind.of(it, pickerDirection) }
        for ((index, kind) in kinds.withIndex()) {
            if (index % columns != 0) ImGui.sameLine(0f, gap)
            val selected = current.kind == kind
            if (cellButton("ease-$index", Easing(kind), selected, cell)) result = Easing(kind)
        }
        if (current.kind == EasingKind.CUSTOM) {
            ImGui.dummy(0f, EditorFonts.px(2f))
            Widgets.smallText("Custom curve: drag the handles in the Inspector or Graph Editor", EditorTheme.TEXT_DIM.u32)
        }
        return result
    }

    private fun cellButton(id: String, easing: Easing, selected: Boolean, size: Float): Boolean {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val pressed = ImGui.invisibleButton(id, size, size)
        val hovered = ImGui.isItemHovered()
        val list = ImGui.getWindowDrawList()
        val background = when {
            selected -> EditorTheme.SELECTION_FILL.u32
            hovered -> EditorTheme.CONTROL_HOVER.u32
            else -> EditorTheme.CONTROL.u32
        }
        list.addRectFilled(x, y, x + size, y + size, background, EditorFonts.px(6f))
        if (selected) list.addRect(x, y, x + size, y + size, EditorTheme.SELECTION.u32, EditorFonts.px(6f), 0, 1.5f)
        val pad = EditorFonts.px(10f)
        val labelHeight = EditorFonts.px(14f)
        curve(
            list, x + pad, y + pad - EditorFonts.px(2f), size - pad * 2f, size - pad * 2f - labelHeight + EditorFonts.px(2f), easing,
            if (selected || hovered) EditorTheme.TEXT.u32 else EditorTheme.ACCENT_TEXT.u32, 1.5f, 24, 0.18f
        )
        EditorFonts.with(EditorFonts.small) {
            val text = easing.kind.family.label
            val width = Widgets.textWidth(text)
            list.addText(
                x + (size - width) / 2f,
                y + size - labelHeight - EditorFonts.px(1f),
                if (selected) EditorTheme.TEXT.u32 else EditorTheme.TEXT_MUTED.u32,
                text
            )
        }
        if (hovered) Widgets.hint(easing.label)
        if (hovered) Widgets.cursorHand()
        return pressed
    }

    fun menu(current: Easing): Easing? {
        var result: Easing? = null
        for (kind in listOf(EasingKind.LINEAR, EasingKind.EASE, EasingKind.EASE_IN, EasingKind.EASE_OUT)) {
            if (Menus.item(kind.label, "", current.kind == kind)) result = Easing(kind)
        }
        ImGui.separator()
        for (family in EasingFamily.entries) {
            if (family == EasingFamily.LINEAR || family == EasingFamily.EASE || family == EasingFamily.HOLD || family == EasingFamily.CUSTOM) continue
            if (ImGui.beginMenu(family.label)) {
                for (direction in listOf(EaseDirection.IN, EaseDirection.OUT, EaseDirection.IN_OUT)) {
                    val kind = EasingKind.of(family, direction) ?: continue
                    if (Menus.item(kind.label, "", current.kind == kind)) result = Easing(kind)
                }
                ImGui.endMenu()
            }
        }
        ImGui.separator()
        if (Menus.item(EasingKind.HOLD.label, "", current.kind == EasingKind.HOLD)) result = Easing.HOLD
        return result
    }

    fun editor(id: String, easing: Easing, size: Float): Easing? {
        val overshoot = 0.35f
        val span = 1f + overshoot * 2f
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        ImGui.invisibleButton("$id-canvas", size, size)
        val hovered = ImGui.isItemHovered()
        val list = ImGui.getWindowDrawList()
        list.addRectFilled(x, y, x + size, y + size, EditorTheme.FIELD.u32, EditorFonts.px(6f))
        fun px(t: Double): Float = x + size * t.toFloat()
        fun py(v: Double): Float = y + size * (1f - (v.toFloat() + overshoot) / span)
        val grid = EditorTheme.TEXT.u32(0.06f)
        for (step in 0..4) {
            val t = step / 4.0
            list.addLine(px(t), py(-overshoot.toDouble()), px(t), py(1.0 + overshoot), grid, 1f)
            list.addLine(px(0.0), py(t), px(1.0), py(t), grid, 1f)
        }
        list.addRectFilled(px(0.0), py(1.0), px(1.0), py(0.0), EditorTheme.TEXT.u32(0.03f))
        list.addLine(px(0.0), py(0.0), px(1.0), py(1.0), EditorTheme.TEXT.u32(0.12f), 1f)
        curve(list, px(0.0), py(1.0 + overshoot), size, size, easing, EditorTheme.ACCENT_TEXT.u32, 2f, 64, overshoot)

        if (!easing.hasHandles) {
            EditorFonts.with(EditorFonts.small) {
                val text = "No handles for ${easing.label.lowercase()}"
                list.addText(
                    x + (size - Widgets.textWidth(text)) / 2f,
                    y + size - EditorFonts.px(18f),
                    EditorTheme.TEXT_DIM.u32,
                    text
                )
            }
            return null
        }
        val handles = easing.toCustom()
        val h1x = px(handles.x1)
        val h1y = py(handles.y1)
        val h2x = px(handles.x2)
        val h2y = py(handles.y2)
        list.addLine(px(0.0), py(0.0), h1x, h1y, EditorTheme.PURPLE.u32(0.8f), 1.5f)
        list.addLine(px(1.0), py(1.0), h2x, h2y, EditorTheme.PURPLE.u32(0.8f), 1.5f)
        val radius = EditorFonts.px(5f)
        val mouseX = ImGui.getMousePosX()
        val mouseY = ImGui.getMousePosY()
        val grab = radius * 2.2f
        val over1 = hovered && abs(mouseX - h1x) <= grab && abs(mouseY - h1y) <= grab
        val over2 = hovered && !over1 && abs(mouseX - h2x) <= grab && abs(mouseY - h2y) <= grab
        val outId = "$id-out"
        val inId = "$id-in"
        if (activeHandle == null && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            if (over1) activeHandle = outId else if (over2) activeHandle = inId
        }
        val dragging = activeHandle == outId || activeHandle == inId
        if (dragging && !ImGui.isMouseDown(ImGuiMouseButton.Left)) activeHandle = null
        list.addCircleFilled(
            h1x,
            h1y,
            radius,
            if (over1 || activeHandle == outId) EditorTheme.TEXT.u32 else EditorTheme.PURPLE.u32,
            16
        )
        list.addCircleFilled(
            h2x,
            h2y,
            radius,
            if (over2 || activeHandle == inId) EditorTheme.TEXT.u32 else EditorTheme.PURPLE.u32,
            16
        )
        list.addCircleFilled(px(0.0), py(0.0), radius * 0.7f, EditorTheme.TEXT_MUTED.u32, 12)
        list.addCircleFilled(px(1.0), py(1.0), radius * 0.7f, EditorTheme.TEXT_MUTED.u32, 12)
        if (over1 || over2 || dragging) ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
        if (!dragging || !ImGui.isMouseDown(ImGuiMouseButton.Left)) return null
        val tx = ((mouseX - x) / size).toDouble().coerceIn(0.0, 1.0)
        val tv =
            ((1f - (mouseY - y) / size) * span - overshoot).toDouble().coerceIn(-overshoot.toDouble(), 1.0 + overshoot)
        val snapped = if (ImGui.getIO().keyCtrl) (tv * 4.0).roundToInt() / 4.0 else tv
        val snappedX = if (ImGui.getIO().keyCtrl) (tx * 4.0).roundToInt() / 4.0 else tx
        val next = if (activeHandle == outId) handles.withOut(snappedX, snapped) else handles.withIn(snappedX, snapped)
        return if (next == easing) null else next
    }

    fun fields(id: String, easing: Easing): Easing? {
        if (!easing.hasHandles) return null
        val custom = easing.toCustom()
        val width = (ImGui.getContentRegionAvailX() - EditorFonts.px(4f) * 3f) / 4f
        var result: Easing? = null
        val values = doubleArrayOf(custom.x1, custom.y1, custom.x2, custom.y2)
        val labels = arrayOf("x1", "y1", "x2", "y2")
        for (index in 0 until 4) {
            if (index > 0) ImGui.sameLine(0f, EditorFonts.px(4f))
            ImGui.setNextItemWidth(width)
            Widgets.doubleDrag("##$id-$index", values[index], 0.005f, "${labels[index]} %.2f")?.let { value ->
                values[index] = value
                result = Easing.bezier(values[0], values[1], values[2], values[3])
            }
        }
        return result
    }
}
