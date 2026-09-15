package gg.sona.afterimage.editor.imgui

import imgui.ImDrawList
import imgui.ImFont
import imgui.ImGui
import imgui.ImVec2
import imgui.flag.*
import imgui.type.ImInt
import imgui.type.ImString

object Widgets {

    private val measure = ImVec2()

    fun help(text: String) {
        ImGui.sameLine()
        Icons.inline(Icon.INFO, ImGui.getFontSize() * 0.9f, EditorTheme.TEXT_DIM.u32)
        tooltip(text)
    }

    fun tooltip(text: String) {
        if (!ImGui.isItemHovered()) return
        hint(text)
    }

    fun hint(text: String) {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, EditorFonts.px(9f), EditorFonts.px(6f))
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, EditorFonts.px(6f))
        ImGui.beginTooltip()
        EditorFonts.with(EditorFonts.small) {
            val wrap = ImGui.getFontSize() * 30f
            ImGui.pushTextWrapPos(wrap)
            val split = KeyCaps.split(text)
            if (split == null) ImGui.textUnformatted(text) else {
                val (description, keys) = split
                ImGui.textUnformatted(description)
                if (textWidth(description) + KeyCaps.piecesWidth(keys) + EditorFonts.px(10f) <= wrap) ImGui.sameLine(0f, EditorFonts.px(10f))
                else ImGui.setCursorPosY(ImGui.getCursorPosY() + EditorFonts.px(2f))
                KeyCaps.render(keys, color = EditorTheme.TEXT.u32(0.85f), textColor = EditorTheme.TEXT_DIM.u32)
            }
            ImGui.popTextWrapPos()
        }
        ImGui.endTooltip()
        ImGui.popStyleVar(2)
    }

    fun beginPopup(id: String): Boolean {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, EditorFonts.px(10f), EditorFonts.px(8f))
        Menus.pushMenuStyle()
        val open = ImGui.beginPopup(id)
        if (!open) ImGui.popStyleVar(2)
        return open
    }

    fun beginContextPopup(id: String): Boolean {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, EditorFonts.px(10f), EditorFonts.px(8f))
        Menus.pushMenuStyle()
        val open = ImGui.beginPopupContextItem(id)
        if (!open) ImGui.popStyleVar(2)
        return open
    }

    fun endPopup() {
        ImGui.endPopup()
        ImGui.popStyleVar(2)
    }

    inline fun <reified E : Enum<E>> enumCombo(label: String, current: E, labelOf: (E) -> String = { it.name }): E? {
        val values = enumValues<E>()
        val names = values.map(labelOf)
        return popupChoice(label, names, current.ordinal)?.let { values[it] }
    }

    fun popupButton(id: String, label: String, width: Float = -1f, muted: Boolean = false): Boolean {
        val height = ImGui.getFrameHeight()
        val actual = if (width > 0f) width else ImGui.getContentRegionAvailX()
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val pressed = ImGui.invisibleButton(id, maxOf(1f, actual), height)
        val hovered = ImGui.isItemHovered()
        val list = ImGui.getWindowDrawList()
        list.addRectFilled(
            x,
            y,
            x + actual,
            y + height,
            if (hovered) EditorTheme.CONTROL_HOVER.u32 else EditorTheme.CONTROL.u32,
            EditorFonts.px(5f)
        )
        list.addText(
            x + EditorFonts.px(9f),
            y + (height - ImGui.getFontSize()) / 2f,
            if (muted) EditorTheme.TEXT_MUTED.u32 else EditorTheme.TEXT.u32,
            clip(label, actual - EditorFonts.px(30f))
        )
        Icons.draw(
            list,
            Icon.CHEVRON_DOWN,
            x + actual - EditorFonts.px(17f),
            y + (height - EditorFonts.px(9f)) / 2f,
            EditorFonts.px(9f),
            EditorTheme.TEXT_DIM.u32
        )
        if (pressed) ImGui.openPopup("$id-menu")
        return beginPopup("$id-menu")
    }

    fun smallButton(label: String): Boolean {
        EditorTheme.pushCompactFrame()
        val pressed = try {
            ghostButton(label)
        } finally {
            ImGui.popStyleVar()
        }
        return pressed
    }

    fun smallButtons(actions: List<Pair<String, () -> Unit>>) {
        EditorTheme.pushCompactFrame()
        try {
            for ((index, action) in actions.withIndex()) {
                val (label, run) = action
                if (index > 0) ImGui.sameLine()
                if (ghostButton(label)) run()
            }
        } finally {
            ImGui.popStyleVar()
        }
    }

    fun progress(fraction: Float, width: Float = -1f, label: String = "") {
        val actual = if (width > 0f) width else ImGui.getContentRegionAvailX() + minOf(0f, width)
        val height = EditorFonts.px(6f)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY() + (ImGui.getFrameHeight() - height) / 2f
        val list = ImGui.getWindowDrawList()
        list.addRectFilled(x, y, x + actual, y + height, EditorTheme.CONTROL.u32, height / 2f)
        if (fraction > 0f) list.addRectFilled(
            x,
            y,
            x + actual * fraction.coerceIn(0f, 1f),
            y + height,
            EditorTheme.ACCENT_TEXT.u32,
            height / 2f
        )
        ImGui.dummy(actual, ImGui.getFrameHeight())
        if (label.isNotEmpty()) {
            ImGui.sameLine()
            smallText(label, EditorTheme.TEXT_DIM.u32)
        }
    }

    fun doubleSlider(label: String, value: Double, min: Double, max: Double, format: String = "%.2f"): Double? =
        slider(label, value.toFloat(), min.toFloat(), max.toFloat(), format)?.toDouble()

    fun slider(
        id: String,
        value: Float,
        min: Float,
        max: Float,
        format: String = "%.2f",
        width: Float = -1f,
        labelOf: ((Float) -> String)? = null
    ): Float? {
        val available = if (width > 0f) width else ImGui.getContentRegionAvailX()
        val height = ImGui.getFrameHeight()
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val label = labelOf?.invoke(value) ?: String.format(format, value)
        EditorFonts.with(EditorFonts.small) { ImGui.calcTextSize(measure, label) }
        val labelWidth = measure.x + EditorFonts.px(10f)
        val trackWidth = maxOf(EditorFonts.px(30f), available - labelWidth)
        val knob = EditorFonts.px(7f)
        ImGui.invisibleButton(id, available, height)
        val hovered = ImGui.isItemHovered()
        val active = ImGui.isItemActive()
        var result: Float? = null
        val range = max - min
        if (active) {
            val mouse = ImGui.getMousePosX()
            var t = ((mouse - (x + knob)) / (trackWidth - knob * 2f)).coerceIn(0f, 1f)
            if (ImGui.getIO().keyShift) t = Math.round(t * 20f) / 20f
            val next = min + t * range
            if (next != value) result = next
        }
        val list = ImGui.getWindowDrawList()
        val shown = result ?: value
        val t = if (range > 0f) ((shown - min) / range).coerceIn(0f, 1f) else 0f
        val cy = y + height / 2f
        val trackHeight = EditorFonts.px(4f)
        val left = x + knob
        val right = x + trackWidth - knob
        list.addRectFilled(
            x,
            cy - trackHeight / 2f,
            x + trackWidth,
            cy + trackHeight / 2f,
            EditorTheme.CONTROL_HOVER.u32,
            trackHeight / 2f
        )
        val kx = left + (right - left) * t
        list.addRectFilled(
            x,
            cy - trackHeight / 2f,
            kx,
            cy + trackHeight / 2f,
            EditorTheme.ACCENT.u32,
            trackHeight / 2f
        )
        list.addCircleFilled(kx, cy, knob + if (active) 1f else 0f, EditorTheme.TEXT.u32, 20)
        if (hovered || active) list.addCircle(kx, cy, knob + 1f, EditorTheme.TEXT.u32(0.3f), 20, 1.5f)
        EditorFonts.with(EditorFonts.small) {
            list.addText(
                x + trackWidth + EditorFonts.px(8f),
                cy - ImGui.getFontSize() / 2f,
                EditorTheme.TEXT_MUTED.u32,
                labelOf?.invoke(shown) ?: String.format(format, shown)
            )
        }
        return result
    }

    fun doubleDrag(
        label: String,
        value: Double,
        speed: Float = 0.1f,
        format: String = "%.3f",
        min: Float = 0f,
        max: Float = 0f
    ): Double? {
        val holder = floatArrayOf(value.toFloat())
        return if (ImGui.dragFloat(label, holder, speed, min, max, format)) holder[0].toDouble() else null
    }

    fun intInput(label: String, value: Int, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int? {
        val holder = ImInt(value)
        return if (ImGui.inputInt(label, holder)) holder.get().coerceIn(min, max) else null
    }

    fun intDrag(label: String, value: Int, speed: Float = 1f, min: Int = 0, max: Int = 0, format: String = "%d"): Int? {
        val holder = intArrayOf(value)
        return if (ImGui.dragInt(label, holder, speed, min, max, format)) holder[0] else null
    }

    fun vector3(
        label: String,
        x: Double,
        y: Double,
        z: Double,
        speed: Float = 0.1f,
        format: String = "%.2f"
    ): DoubleArray? {
        val holder = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())
        val width = ImGui.calcItemWidth()
        val gap = EditorFonts.px(4f)
        val each = (width - gap * 2f) / 3f
        var changed = false
        ImGui.pushID(label)
        for (index in 0 until 3) {
            if (index > 0) ImGui.sameLine(0f, gap)
            val colour = AXIS_COLOURS[index]
            val fx = ImGui.getCursorScreenPosX()
            val fy = ImGui.getCursorScreenPosY()
            ImGui.setNextItemWidth(each)
            val single = floatArrayOf(holder[index])
            ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, EditorFonts.px(12f), ImGui.getStyle().framePaddingY)
            if (ImGui.dragFloat("##$index", single, speed, 0f, 0f, format)) {
                holder[index] = single[0]
                changed = true
            }
            ImGui.popStyleVar()
            ImGui.getWindowDrawList().addRectFilled(
                fx,
                fy + EditorFonts.px(4f),
                fx + EditorFonts.px(3f),
                fy + ImGui.getFrameHeight() - EditorFonts.px(4f),
                colour.u32,
                1.5f
            )
        }
        ImGui.popID()
        return if (changed) doubleArrayOf(holder[0].toDouble(), holder[1].toDouble(), holder[2].toDouble()) else null
    }

    fun textInput(label: String, value: String, capacity: Int = 256, flags: Int = ImGuiInputTextFlags.None): String? {
        val holder = ImString(value, capacity)
        return if (ImGui.inputText(label, holder, flags)) holder.get() else null
    }

    fun toggle(label: String, value: Boolean, enabled: Boolean = true): Boolean? {
        val width = EditorFonts.px(30f)
        val height = EditorFonts.px(18f)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY() + (ImGui.getFrameHeight() - height) / 2f
        if (!enabled) ImGui.beginDisabled()
        val pressed = ImGui.invisibleButton(label, width, ImGui.getFrameHeight())
        if (!enabled) ImGui.endDisabled()
        val hovered = enabled && ImGui.isItemHovered()
        val held = enabled && ImGui.isItemActive()
        val list = ImGui.getWindowDrawList()
        val on = if (pressed) !value else value
        val id = ImGui.getItemID()
        val target = if (on) 1f else 0f
        val previous = toggleAnim[id] ?: target
        val speed = ImGui.getIO().deltaTime * 14f
        val t = if (previous == target) target else previous + (target - previous).coerceIn(-speed, speed)
        toggleAnim[id] = t
        val onColor = if (hovered) EditorTheme.SWITCH_ON_HOVER else EditorTheme.SWITCH_ON
        val offColor = if (hovered) EditorTheme.SWITCH_OFF_HOVER else EditorTheme.SWITCH_OFF
        val track = if (!enabled) mix(EditorTheme.SWITCH_OFF, EditorTheme.SWITCH_ON, t, 0.4f) else mix(offColor, onColor, t, 1f)
        list.addRectFilled(x, y, x + width, y + height, track, height / 2f)
        list.addRect(x, y, x + width, y + height, EditorTheme.BORDER_SOFT.u32(if (on) 0.05f else 0.12f), height / 2f, 0, 1f)
        val inset = EditorFonts.px(2f)
        val knob = height - inset * 2f
        val knobRadius = knob / 2f * (if (held) 1.06f else 1f)
        val left = x + inset + knob / 2f
        val right = x + width - inset - knob / 2f
        val kx = left + (right - left) * t
        val ky = y + height / 2f
        list.addCircleFilled(kx, ky + 1f, knobRadius, EditorTheme.PANEL_SUNKEN.u32(if (enabled) 0.45f else 0.2f), 24)
        list.addCircleFilled(kx, ky, knobRadius, if (enabled) EditorTheme.SWITCH_KNOB.u32 else EditorTheme.TEXT_MUTED.u32, 24)
        if (hovered) cursorHand()
        val visible = label.substringBefore("##")
        if (visible.isNotEmpty()) {
            ImGui.sameLine()
            ImGui.alignTextToFramePadding()
            ImGui.textUnformatted(visible)
        }
        return if (pressed) !value else null
    }

    private fun mix(from: EditorTheme.Rgb, to: EditorTheme.Rgb, t: Float, alpha: Float): Int =
        ImGui.getColorU32(from.r + (to.r - from.r) * t, from.g + (to.g - from.g) * t, from.b + (to.b - from.b) * t, alpha)

    private val toggleAnim = HashMap<Int, Float>()

    fun section(title: String, defaultOpen: Boolean = true): Boolean {
        ImGui.pushStyleColor(ImGuiCol.Header, 0)
        ImGui.pushStyleColor(ImGuiCol.HeaderHovered, EditorTheme.CONTROL.u32)
        ImGui.pushStyleColor(ImGuiCol.HeaderActive, EditorTheme.CONTROL_HOVER.u32)
        val open = EditorFonts.with(EditorFonts.bodyMedium) {
            ImGui.collapsingHeader(
                title,
                if (defaultOpen) imgui.flag.ImGuiTreeNodeFlags.DefaultOpen else 0
            )
        }
        ImGui.popStyleColor(3)
        return open
    }

    fun header(text: String, color: Int = EditorTheme.TEXT.u32) {
        val x = ImGui.getCursorScreenPosX()
        val width = ImGui.getContentRegionAvailX()
        if (ImGui.getCursorPosY() > ImGui.getStyle().windowPaddingY + EditorFonts.px(4f)) {
            ImGui.dummy(0f, EditorFonts.px(6f))
            val y = ImGui.getCursorScreenPosY()
            ImGui.getWindowDrawList().addLine(x, y, x + width, y, EditorTheme.SEPARATOR.u32, 1f)
            ImGui.dummy(0f, EditorFonts.px(8f))
        }
        EditorFonts.with(EditorFonts.smallMedium) {
            ImGui.pushStyleColor(ImGuiCol.Text, color)
            ImGui.textUnformatted(text)
            ImGui.popStyleColor()
        }
        ImGui.dummy(0f, EditorFonts.px(2f))
    }

    fun foldout(
        title: String,
        key: String,
        toggled: UiPreferences.PersistedSet,
        defaultOpen: Boolean = true,
        trailing: String? = null,
    ): Boolean {
        val open = defaultOpen != (key in toggled)
        val x = ImGui.getCursorScreenPosX()
        val width = ImGui.getContentRegionAvailX()
        val list = ImGui.getWindowDrawList()
        if (ImGui.getCursorPosY() > ImGui.getStyle().windowPaddingY + EditorFonts.px(4f)) {
            ImGui.dummy(0f, EditorFonts.px(4f))
            val y = ImGui.getCursorScreenPosY()
            list.addLine(x, y, x + width, y, EditorTheme.SEPARATOR.u32, 1f)
            ImGui.dummy(0f, EditorFonts.px(2f))
        }
        val height = EditorFonts.px(26f)
        val y = ImGui.getCursorScreenPosY()
        val pressed = ImGui.invisibleButton("##foldout-$key", maxOf(1f, width), height)
        val hovered = ImGui.isItemHovered()
        if (hovered) {
            list.addRectFilled(x, y, x + width, y + height, EditorTheme.TEXT.u32(0.04f), EditorFonts.px(5f))
            cursorHand()
        }
        val chevron = EditorFonts.px(11f)
        Icons.draw(
            list,
            if (open) Icon.CHEVRON_DOWN else Icon.CHEVRON_RIGHT,
            x + EditorFonts.px(3f),
            y + (height - chevron) / 2f,
            chevron,
            EditorTheme.TEXT_MUTED.u32
        )
        EditorFonts.with(EditorFonts.smallMedium) {
            list.addText(x + EditorFonts.px(19f), y + (height - ImGui.getFontSize()) / 2f, EditorTheme.TEXT.u32, title)
        }
        if (trailing != null) EditorFonts.with(EditorFonts.small) {
            val trailingWidth = textWidth(trailing)
            list.addText(
                x + width - trailingWidth - EditorFonts.px(4f),
                y + (height - ImGui.getFontSize()) / 2f,
                EditorTheme.TEXT_DIM.u32,
                trailing
            )
        }
        if (pressed) toggled.toggle(key, key !in toggled)
        if (open) ImGui.dummy(0f, EditorFonts.px(2f))
        return open
    }

    fun chipButton(
        id: String,
        text: String,
        selected: Boolean = false,
        lineHeight: Float = ImGui.getFrameHeight(),
        tooltipText: String? = null,
    ): Boolean {
        val list = ImGui.getWindowDrawList()
        return EditorFonts.with(EditorFonts.smallMedium) {
            ImGui.calcTextSize(measure, text)
            val padX = EditorFonts.px(8f)
            val height = measure.y + EditorFonts.px(7f)
            val width = measure.x + padX * 2f
            val x = ImGui.getCursorScreenPosX()
            val top = ImGui.getCursorScreenPosY()
            val y = top + maxOf(0f, (lineHeight - height) / 2f)
            ImGui.setCursorScreenPos(x, y)
            val pressed = ImGui.invisibleButton(id, width, height)
            val hovered = ImGui.isItemHovered()
            val background = when {
                selected -> EditorTheme.CONTROL_ACTIVE.u32
                hovered -> EditorTheme.CONTROL_HOVER.u32
                else -> EditorTheme.CONTROL.u32
            }
            list.addRectFilled(x, y, x + width, y + height, background, height / 2f)
            list.addText(x + padX, y + EditorFonts.px(3.5f), if (selected) EditorTheme.TEXT.u32 else EditorTheme.TEXT_MUTED.u32, text)
            if (hovered) {
                cursorHand()
                if (tooltipText != null) hint(tooltipText)
            }
            ImGui.setCursorScreenPos(x, top)
            ImGui.dummy(width, maxOf(height, lineHeight))
            pressed
        }
    }

    fun accentButton(label: String, width: Float = 0f, height: Float = 0f): Boolean =
        styledButton(label, width, height, ButtonStyle.ACCENT)

    fun dangerButton(label: String, width: Float = 0f): Boolean = styledButton(label, width, 0f, ButtonStyle.DANGER)

    fun ghostButton(label: String, width: Float = 0f): Boolean = styledButton(label, width, 0f, ButtonStyle.GHOST)

    fun button(label: String, width: Float = 0f): Boolean = styledButton(label, width, 0f, ButtonStyle.NORMAL)

    private fun styledButton(label: String, width: Float, height: Float, style: ButtonStyle): Boolean =
        iconLabelButton(label, iconFor(label), label.substringBefore("##"), width, style, height = height)

    private fun buttonFont(style: ButtonStyle): ImFont =
        if (style == ButtonStyle.ACCENT) EditorFonts.bodyMedium else EditorFonts.body

    private fun buttonIconSize(): Float = ImGui.getFontSize() * 0.95f

    private fun buttonPadding(): Float = ImGui.getStyle().framePaddingX + EditorFonts.px(4f)

    fun buttonWidth(label: String, style: ButtonStyle = ButtonStyle.NORMAL): Float =
        buttonWidth(iconFor(label), label.substringBefore("##"), style)

    fun buttonWidth(icon: Icon?, text: String, style: ButtonStyle = ButtonStyle.NORMAL): Float {
        val textWidth = if (text.isEmpty()) 0f else EditorFonts.with(buttonFont(style)) { textWidth(text) }
        val iconWidth = if (icon == null) 0f else buttonIconSize() + (if (text.isEmpty()) 0f else BUTTON_ICON_GAP)
        return iconWidth + textWidth + buttonPadding() * 2f
    }

    private fun wrapIfNeeded(width: Float) {
        if (width <= ImGui.getContentRegionAvailX() + 1f) return
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val previousTop = ImGui.getItemRectMinY() - y
        val previousRight = x - ImGui.getItemRectMaxX()
        val continues = previousTop > -0.5f && previousTop < EditorFonts.px(12f) &&
                previousRight > -0.5f && previousRight < EditorFonts.px(32f)
        if (continues) ImGui.newLine()
    }

    fun rightAlign(vararg widths: Float, spacing: Float = EditorFonts.px(8f)) {
        val total = widths.sum() + spacing * (widths.size - 1).coerceAtLeast(0)
        ImGui.setCursorPosX(ImGui.getCursorPosX() + (ImGui.getContentRegionAvailX() - total).coerceAtLeast(0f))
    }

    fun lastWidth(id: String): Float = groupWidths[ImGui.getID(id)] ?: 0f

    inline fun measured(id: String, block: () -> Unit): Float {
        ImGui.beginGroup()
        try {
            block()
        } finally {
            ImGui.endGroup()
        }
        return remember(id, ImGui.getItemRectSizeX())
    }

    fun remember(id: String, width: Float): Float {
        groupWidths[ImGui.getID(id)] = width
        return width
    }

    fun centerInRow(rowTop: Float, rowHeight: Float, itemHeight: Float) {
        ImGui.setCursorScreenPos(ImGui.getCursorScreenPosX(), rowTop + (rowHeight - itemHeight) / 2f)
    }

    private fun background(style: ButtonStyle, hovered: Boolean, held: Boolean, enabled: Boolean): Int = when (style) {
        ButtonStyle.ACCENT -> when {
            !enabled -> EditorTheme.PRIMARY.u32(0.3f)
            held -> EditorTheme.PRIMARY_ACTIVE.u32
            hovered -> EditorTheme.PRIMARY_HOVER.u32
            else -> EditorTheme.PRIMARY.u32
        }

        ButtonStyle.DANGER -> when {
            !enabled -> EditorTheme.RECORD.u32(0.3f)
            held -> EditorTheme.RECORD.u32(0.65f)
            hovered -> EditorTheme.RECORD.u32
            else -> EditorTheme.RECORD.u32(0.82f)
        }

        ButtonStyle.GHOST -> when {
            held -> EditorTheme.CONTROL_ACTIVE.u32
            hovered -> EditorTheme.CONTROL_HOVER.u32
            else -> 0
        }

        ButtonStyle.NORMAL -> when {
            !enabled -> EditorTheme.CONTROL.u32(0.5f)
            held -> EditorTheme.CONTROL_ACTIVE.u32
            hovered -> EditorTheme.CONTROL_HOVER.u32
            else -> EditorTheme.CONTROL.u32
        }
    }

    fun iconButton(
        id: String,
        icon: Icon,
        size: Float = EditorFonts.px(24f),
        tooltip: String? = null,
        active: Boolean = false,
        enabled: Boolean = true,
        color: Int = EditorTheme.TEXT.u32,
        iconScale: Float = 0.6f,
        rounded: Float = EditorFonts.px(5f),
        width: Float = 0f,
    ): Boolean {
        val actualWidth = if (width > 0f) width else size
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        if (!enabled) ImGui.beginDisabled()
        val pressed = ImGui.invisibleButton(id, actualWidth, size)
        if (!enabled) ImGui.endDisabled()
        val hovered = enabled && ImGui.isItemHovered()
        val held = enabled && ImGui.isItemActive()
        val list = ImGui.getWindowDrawList()
        val background = when {
            held -> EditorTheme.CONTROL_ACTIVE.u32
            active -> if (hovered) EditorTheme.ACCENT.u32 else EditorTheme.CONTROL_ACTIVE.u32
            hovered -> EditorTheme.CONTROL_HOVER.u32
            else -> 0
        }
        if (background != 0) list.addRectFilled(x, y, x + actualWidth, y + size, background, rounded)
        val tint = when {
            !enabled -> EditorTheme.TEXT_DIM.u32
            active -> 0xFFFFFFFF.toInt()
            else -> color
        }
        val iconSize = size * iconScale
        Icons.draw(list, icon, x + (actualWidth - iconSize) / 2f, y + (size - iconSize) / 2f, iconSize, tint)
        if (tooltip != null && hovered) hint(tooltip)
        return pressed
    }

    fun iconToggle(
        id: String,
        icon: Icon,
        value: Boolean,
        size: Float = EditorFonts.px(24f),
        tooltip: String? = null,
        activeColor: Int = EditorTheme.ACCENT_TEXT.u32
    ): Boolean? {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val pressed = ImGui.invisibleButton(id, size, size)
        val hovered = ImGui.isItemHovered()
        val list = ImGui.getWindowDrawList()
        if (hovered) list.addRectFilled(x, y, x + size, y + size, EditorTheme.CONTROL_HOVER.u32, EditorFonts.px(5f))
        else if (value) list.addRectFilled(x, y, x + size, y + size, EditorTheme.CONTROL.u32, EditorFonts.px(5f))
        val iconSize = size * 0.6f
        Icons.draw(
            list,
            icon,
            x + (size - iconSize) / 2f,
            y + (size - iconSize) / 2f,
            iconSize,
            if (value) activeColor else EditorTheme.TEXT_DIM.u32
        )
        if (tooltip != null && hovered) hint(tooltip)
        return if (pressed) !value else null
    }

    fun segmented(
        id: String,
        options: List<String>,
        selected: Int,
        itemWidth: Float = 0f,
        tooltips: List<String>? = null,
        reserve: Float = 0f
    ): Int? {
        var result: Int? = null
        val height = ImGui.getFrameHeight()
        val pad = EditorFonts.px(2f)
        val gap = EditorFonts.px(2f)
        val widths = options.map { if (itemWidth > 0f) itemWidth else textWidth(it) + EditorFonts.px(18f) }
        val available = ImGui.getContentRegionAvailX() - reserve
        val natural = widths.sum() + pad * 2f + gap * (options.size - 1)
        if (itemWidth <= 0f && natural > available) return popupChoice(id, options, selected, tooltips, available)
        val total = if (itemWidth > 0f) natural else maxOf(natural, minOf(available, natural * 1.6f))
        val scale = if (itemWidth > 0f) 1f else (total - pad * 2f - gap * (options.size - 1)) / widths.sum()
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val list = ImGui.getWindowDrawList()
        list.addRectFilled(x, y, x + total, y + height, EditorTheme.CONTROL.u32, EditorFonts.px(6f))
        var cursor = x + pad
        ImGui.pushID(id)
        for ((index, option) in options.withIndex()) {
            val width = widths[index] * scale
            ImGui.setCursorScreenPos(cursor, y + pad)
            val pressed = ImGui.invisibleButton("seg$index", width, height - pad * 2f)
            val hovered = ImGui.isItemHovered()
            val active = index == selected
            if (active) {
                list.addRectFilled(
                    cursor,
                    y + pad,
                    cursor + width,
                    y + height - pad,
                    EditorTheme.CONTROL_ACTIVE.u32,
                    EditorFonts.px(5f)
                )
            } else if (hovered) {
                list.addRectFilled(
                    cursor,
                    y + pad,
                    cursor + width,
                    y + height - pad,
                    EditorTheme.CONTROL_HOVER.u32,
                    EditorFonts.px(5f)
                )
            }
            val font = if (active) EditorFonts.bodyMedium else EditorFonts.body
            val labelWidth = EditorFonts.with(font) { textWidth(option) }
            list.addText(
                font,
                ImGui.getFontSize().toInt(),
                cursor + (width - labelWidth) / 2f,
                y + (height - ImGui.getFontSize()) / 2f,
                if (active) EditorTheme.TEXT.u32 else EditorTheme.TEXT_MUTED.u32,
                option
            )
            if (pressed && !active) result = index
            tooltips?.getOrNull(index)?.let { if (hovered) hint(it) }
            cursor += width + gap
        }
        ImGui.popID()
        ImGui.setCursorScreenPos(x, y)
        ImGui.dummy(total, height)
        return result
    }

    fun popupChoice(
        id: String,
        options: List<String>,
        selected: Int,
        tooltips: List<String>? = null,
        width: Float = -1f
    ): Int? {
        var result: Int? = null
        val height = ImGui.getFrameHeight()
        val actual = if (width > 0f) width else ImGui.getContentRegionAvailX()
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val pressed = ImGui.invisibleButton(id, maxOf(1f, actual), height)
        val hovered = ImGui.isItemHovered()
        val list = ImGui.getWindowDrawList()
        list.addRectFilled(
            x,
            y,
            x + actual,
            y + height,
            if (hovered) EditorTheme.CONTROL_HOVER.u32 else EditorTheme.CONTROL.u32,
            EditorFonts.px(5f)
        )
        val label = options.getOrNull(selected) ?: "Choose"
        list.addText(
            x + EditorFonts.px(9f),
            y + (height - ImGui.getFontSize()) / 2f,
            if (selected >= 0) EditorTheme.TEXT.u32 else EditorTheme.TEXT_MUTED.u32,
            clip(label, actual - EditorFonts.px(30f))
        )
        Icons.draw(
            list,
            Icon.CHEVRON_DOWN,
            x + actual - EditorFonts.px(17f),
            y + (height - EditorFonts.px(9f)) / 2f,
            EditorFonts.px(9f),
            EditorTheme.TEXT_DIM.u32
        )
        if (pressed) ImGui.openPopup("$id-menu")
        if (beginPopup("$id-menu")) {
            for ((index, option) in options.withIndex()) {
                if (Menus.item(option, "", index == selected) && index != selected) result = index
                tooltips?.getOrNull(index)?.let { tooltip(it) }
            }
            endPopup()
        }
        return result
    }

    fun segmentedIcons(
        id: String,
        icons: List<Icon>,
        selected: Int,
        tooltips: List<String>? = null,
        size: Float = EditorFonts.px(24f),
        colors: List<Int>? = null
    ): Int? {
        var result: Int? = null
        val pad = EditorFonts.px(2f)
        val gap = EditorFonts.px(1f)
        val cell = size + EditorFonts.px(6f)
        val total = icons.size * cell + pad * 2f + gap * (icons.size - 1)
        val height = size + pad * 2f
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val list = ImGui.getWindowDrawList()
        list.addRectFilled(x, y, x + total, y + height, EditorTheme.CONTROL.u32, EditorFonts.px(6f))
        var cursor = x + pad
        ImGui.pushID(id)
        for ((index, icon) in icons.withIndex()) {
            ImGui.setCursorScreenPos(cursor, y + pad)
            val pressed = ImGui.invisibleButton("seg$index", cell, size)
            val hovered = ImGui.isItemHovered()
            val active = index == selected
            if (active) list.addRectFilled(
                cursor,
                y + pad,
                cursor + cell,
                y + pad + size,
                EditorTheme.CONTROL_ACTIVE.u32,
                EditorFonts.px(5f)
            )
            else if (hovered) list.addRectFilled(
                cursor,
                y + pad,
                cursor + cell,
                y + pad + size,
                EditorTheme.CONTROL_HOVER.u32,
                EditorFonts.px(5f)
            )
            val iconSize = size * 0.6f
            val tint = colors?.getOrNull(index) ?: if (active) EditorTheme.TEXT.u32 else EditorTheme.TEXT_MUTED.u32
            Icons.draw(list, icon, cursor + (cell - iconSize) / 2f, y + pad + (size - iconSize) / 2f, iconSize, tint)
            if (pressed && !active) result = index
            tooltips?.getOrNull(index)?.let { if (hovered) hint(it) }
            cursor += cell + gap
        }
        ImGui.popID()
        ImGui.setCursorScreenPos(x, y)
        ImGui.dummy(total, height)
        return result
    }

    fun search(id: String, value: ImString, hint: String = "Search", width: Float = -1f, flags: Int = 0): Boolean {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val iconSize = ImGui.getFontSize() * 0.95f
        ImGui.setNextItemWidth(width)
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, iconSize + EditorFonts.px(12f), ImGui.getStyle().framePaddingY)
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, ImGui.getFrameHeight() / 2f)
        val changed = ImGui.inputTextWithHint(id, hint, value, flags)
        ImGui.popStyleVar(2)
        Icons.draw(
            ImGui.getWindowDrawList(),
            Icon.SEARCH,
            x + EditorFonts.px(8f),
            y + (ImGui.getFrameHeight() - iconSize) / 2f,
            iconSize,
            EditorTheme.TEXT_DIM.u32
        )
        return changed
    }

    fun pill(text: String, color: EditorTheme.Rgb, textColor: Int = color.u32) {
        val list = ImGui.getWindowDrawList()
        EditorFonts.with(EditorFonts.smallMedium) {
            ImGui.calcTextSize(measure, text)
            val x = ImGui.getCursorScreenPosX()
            val padX = EditorFonts.px(7f)
            val height = measure.y + EditorFonts.px(5f)
            val y = ImGui.getCursorScreenPosY() + maxOf(0f, (ImGui.getFrameHeight() - height) / 2f)
            list.addRectFilled(x, y, x + measure.x + padX * 2, y + height, color.u32(0.18f), height / 2f)
            list.addText(x + padX, y + EditorFonts.px(2.5f), textColor, text)
            ImGui.dummy(measure.x + padX * 2, maxOf(height, ImGui.getFrameHeight()))
        }
    }

    fun chip(
        text: String,
        color: Int = EditorTheme.TEXT_MUTED.u32,
        background: Int = EditorTheme.CONTROL.u32,
        lineHeight: Float = ImGui.getFrameHeight(),
    ) {
        val list = ImGui.getWindowDrawList()
        EditorFonts.with(EditorFonts.smallMedium) {
            ImGui.calcTextSize(measure, text)
            val padX = EditorFonts.px(7f)
            val height = measure.y + EditorFonts.px(6f)
            val x = ImGui.getCursorScreenPosX()
            val y = ImGui.getCursorScreenPosY() + maxOf(0f, (lineHeight - height) / 2f)
            list.addRectFilled(x, y, x + measure.x + padX * 2f, y + height, background, EditorFonts.px(5f))
            list.addText(x + padX, y + EditorFonts.px(3f), color, text)
            ImGui.dummy(measure.x + padX * 2f, maxOf(height, lineHeight))
        }
    }

    fun chipWidth(text: String): Float =
        EditorFonts.with(EditorFonts.smallMedium) { textWidth(text) } + EditorFonts.px(14f)

    fun chipsWidth(parts: List<String>, gap: Float = EditorFonts.px(4f)): Float =
        parts.sumOf { chipWidth(it).toDouble() }.toFloat() + gap * (parts.size - 1).coerceAtLeast(0)

    fun chips(
        parts: List<String>,
        color: Int = EditorTheme.TEXT_MUTED.u32,
        background: Int = EditorTheme.CONTROL.u32,
        lineHeight: Float = ImGui.getFrameHeight(),
        gap: Float = EditorFonts.px(4f),
    ) {
        if (parts.isEmpty()) {
            ImGui.dummy(1f, lineHeight)
            return
        }
        for ((index, part) in parts.withIndex()) {
            if (index > 0) {
                ImGui.sameLine(0f, gap)
                if (ImGui.getContentRegionAvailX() < chipWidth(part)) ImGui.newLine()
            }
            chip(part, color, background, lineHeight)
        }
    }

    fun solidPill(text: String, color: EditorTheme.Rgb) {
        val list = ImGui.getWindowDrawList()
        EditorFonts.with(EditorFonts.smallMedium) {
            ImGui.calcTextSize(measure, text)
            val x = ImGui.getCursorScreenPosX()
            val padX = EditorFonts.px(7f)
            val height = measure.y + EditorFonts.px(5f)
            val y = ImGui.getCursorScreenPosY() + maxOf(0f, (ImGui.getFrameHeight() - height) / 2f)
            list.addRectFilled(x, y, x + measure.x + padX * 2, y + height, color.u32, height / 2f)
            list.addText(x + padX, y + EditorFonts.px(2.5f), 0xFFFFFFFF.toInt(), text)
            ImGui.dummy(measure.x + padX * 2, maxOf(height, ImGui.getFrameHeight()))
        }
    }

    fun tabular(text: String, font: ImFont = EditorFonts.timecode, color: Int = EditorTheme.TEXT.u32) {
        val list = ImGui.getWindowDrawList()
        ImGui.pushFont(font)
        val size = ImGui.getFontSize()
        var digit = 0f
        for (c in '0'..'9') {
            ImGui.calcTextSize(measure, c.toString())
            if (measure.x > digit) digit = measure.x
        }
        var x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val start = x
        for (c in text) {
            val glyph = c.toString()
            ImGui.calcTextSize(measure, glyph)
            if (c.isDigit()) {
                list.addText(font, size, x + (digit - measure.x) / 2f, y, color, glyph)
                x += digit
            } else {
                list.addText(font, size, x, y, color, glyph)
                x += measure.x
            }
        }
        ImGui.dummy(x - start, size.toFloat())
        ImGui.popFont()
    }

    fun tabularWidth(text: String, font: ImFont = EditorFonts.timecode): Float {
        ImGui.pushFont(font)
        var digit = 0f
        for (c in '0'..'9') {
            ImGui.calcTextSize(measure, c.toString())
            if (measure.x > digit) digit = measure.x
        }
        var width = 0f
        for (c in text) {
            if (c.isDigit()) {
                width += digit
            } else {
                ImGui.calcTextSize(measure, c.toString())
                width += measure.x
            }
        }
        ImGui.popFont()
        return width
    }

    fun beginProperties(id: String, labelWidth: Float = 0f): Boolean {
        val labelColumn =
            if (labelWidth > 0f) labelWidth else (ImGui.getContentRegionAvailX() * 0.42f).coerceIn(EditorFonts.px(90f), EditorFonts.px(160f))
        ImGui.pushStyleVar(ImGuiStyleVar.CellPadding, EditorFonts.px(6f), EditorFonts.px(3f))
        val open = ImGui.beginTable(id, 2, ImGuiTableFlags.SizingStretchProp)
        if (open) {
            ImGui.tableSetupColumn("label", ImGuiTableColumnFlags.WidthFixed, labelColumn)
            ImGui.tableSetupColumn("value", ImGuiTableColumnFlags.WidthStretch)
        } else ImGui.popStyleVar()
        return open
    }

    fun property(label: String, tooltipText: String? = null) {
        ImGui.tableNextRow()
        ImGui.tableSetColumnIndex(0)
        ImGui.alignTextToFramePadding()
        val available = ImGui.getContentRegionAvailX()
        val text = if (label.isEmpty()) " " else clip(label, available)
        ImGui.setCursorPosX(ImGui.getCursorPosX() + (available - textWidth(text)).coerceAtLeast(0f))
        ImGui.pushStyleColor(ImGuiCol.Text, if (label.isEmpty()) 0 else EditorTheme.TEXT_MUTED.u32)
        ImGui.textUnformatted(text)
        ImGui.popStyleColor()
        if (tooltipText != null) tooltip(tooltipText)
        ImGui.tableSetColumnIndex(1)
        ImGui.alignTextToFramePadding()
        ImGui.setNextItemWidth(-1f)
    }

    fun endProperties() {
        ImGui.endTable()
        ImGui.popStyleVar()
        ImGui.dummy(0f, EditorFonts.px(4f))
    }

    fun emptyState(title: String, hint: String? = null, icon: Icon? = null) {
        val width = ImGui.getContentRegionAvailX()
        val height = ImGui.getContentRegionAvailY()
        val startY = ImGui.getCursorPosY()
        ImGui.dummy(0f, maxOf(0f, height / 2f - EditorFonts.px(34f)))
        if (icon != null) {
            val size = EditorFonts.px(30f)
            ImGui.setCursorPosX((width - size) / 2f)
            Icons.inline(icon, size, EditorTheme.TEXT_DIM.u32)
            ImGui.dummy(0f, EditorFonts.px(2f))
        }
        EditorFonts.with(EditorFonts.bodyMedium) {
            ImGui.calcTextSize(measure, title)
            ImGui.setCursorPosX(maxOf(0f, (width - measure.x) / 2f))
            ImGui.pushStyleColor(ImGuiCol.Text, EditorTheme.TEXT_MUTED.u32)
            ImGui.textUnformatted(title)
            ImGui.popStyleColor()
        }
        if (hint != null) {
            EditorFonts.with(EditorFonts.small) {
                ImGui.calcTextSize(measure, hint)
                ImGui.setCursorPosX(maxOf(0f, (width - measure.x) / 2f))
                ImGui.pushStyleColor(ImGuiCol.Text, EditorTheme.TEXT_DIM.u32)
                ImGui.textUnformatted(hint)
                ImGui.popStyleColor()
            }
        }
        ImGui.setCursorPosY(startY)
    }

    fun mutedText(text: String) {
        ImGui.pushStyleColor(ImGuiCol.Text, EditorTheme.TEXT_MUTED.u32)
        ImGui.textUnformatted(text)
        ImGui.popStyleColor()
    }

    fun smallText(text: String, color: Int = EditorTheme.TEXT_MUTED.u32, clipToWidth: Boolean = false) {
        EditorFonts.with(EditorFonts.small) {
            ImGui.pushStyleColor(ImGuiCol.Text, color)
            ImGui.textUnformatted(if (clipToWidth) clip(text, ImGui.getContentRegionAvailX()) else text)
            ImGui.popStyleColor()
        }
    }

    fun wrappedText(text: String, color: Int = EditorTheme.TEXT_MUTED.u32) {
        EditorFonts.with(EditorFonts.small) {
            ImGui.pushStyleColor(ImGuiCol.Text, color)
            ImGui.pushTextWrapPos(0f)
            ImGui.textUnformatted(text)
            ImGui.popTextWrapPos()
            ImGui.popStyleColor()
        }
    }

    fun verticalSeparator(height: Float = ImGui.getFrameHeight()) {
        ImGui.sameLine()
        val x = ImGui.getCursorScreenPosX() + EditorFonts.px(4f)
        val y = ImGui.getCursorScreenPosY()
        ImGui.getWindowDrawList().addLine(x, y + height * 0.2f, x, y + height * 0.8f, EditorTheme.SEPARATOR.u32, 1f)
        ImGui.dummy(EditorFonts.px(9f), height)
        ImGui.sameLine()
    }

    fun colorSwatch(id: String, rgb: Int, size: Float = EditorFonts.px(14f), selected: Boolean = false): Boolean {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY() + (ImGui.getFrameHeight() - size) / 2f
        val pressed = ImGui.invisibleButton(id, size, ImGui.getFrameHeight())
        val hovered = ImGui.isItemHovered()
        val list = ImGui.getWindowDrawList()
        list.addCircleFilled(x + size / 2f, y + size / 2f, size / 2f, rgbToU32(rgb), 16)
        if (selected) list.addCircle(x + size / 2f, y + size / 2f, size / 2f + 2f, EditorTheme.TEXT.u32, 16, 1.5f)
        else if (hovered) list.addCircle(x + size / 2f, y + size / 2f, size / 2f + 2f, EditorTheme.TEXT_DIM.u32, 16, 1f)
        return pressed
    }

    fun rgbToU32(rgb: Int, alpha: Float = 1f): Int =
        ImGui.getColorU32(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f, alpha)

    fun color(r: Float, g: Float, b: Float, a: Float = 1f): Int = ImGui.getColorU32(r, g, b, a)

    inline fun <T> withId(id: String, block: () -> T): T {
        ImGui.pushID(id)
        try {
            return block()
        } finally {
            ImGui.popID()
        }
    }

    inline fun <T> withId(id: Int, block: () -> T): T {
        ImGui.pushID(id)
        try {
            return block()
        } finally {
            ImGui.popID()
        }
    }

    enum class ButtonStyle { NORMAL, ACCENT, GHOST, DANGER }

    fun iconLabelButton(
        id: String,
        icon: Icon?,
        label: String,
        width: Float = 0f,
        style: ButtonStyle = ButtonStyle.NORMAL,
        enabled: Boolean = true,
        tooltipText: String? = null,
        height: Float = 0f
    ): Boolean {
        val frameHeight = if (height > 0f) height else ImGui.getFrameHeight()
        val iconSize = buttonIconSize()
        val gap = if (icon == null || label.isEmpty()) 0f else BUTTON_ICON_GAP
        val font = buttonFont(style)
        val labelWidth = if (label.isEmpty()) 0f else EditorFonts.with(font) { textWidth(label) }
        val natural = buttonWidth(icon, label, style)
        val actual =
            if (width > 0f) width else if (width < 0f) ImGui.getContentRegionAvailX() + width else natural
        if (width >= 0f) wrapIfNeeded(actual)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        if (!enabled) ImGui.beginDisabled()
        val pressed = ImGui.invisibleButton(id, actual, frameHeight)
        if (!enabled) ImGui.endDisabled()
        val hovered = enabled && ImGui.isItemHovered()
        val held = enabled && ImGui.isItemActive()
        val list = ImGui.getWindowDrawList()
        val background = background(style, hovered, held, enabled)
        if (background != 0) list.addRectFilled(
            x,
            y,
            x + actual,
            y + frameHeight,
            background,
            ImGui.getStyle().frameRounding
        )
        val tint = when {
            !enabled -> EditorTheme.TEXT_DIM.u32
            style == ButtonStyle.ACCENT -> EditorTheme.PRIMARY_TEXT.u32
            style == ButtonStyle.DANGER -> 0xFFFFFFFF.toInt()
            else -> EditorTheme.TEXT.u32
        }
        val iconWidth = if (icon == null) 0f else iconSize
        val startX = x + (actual - (iconWidth + gap + labelWidth)) / 2f
        if (icon != null) Icons.draw(list, icon, startX, y + (frameHeight - iconSize) / 2f, iconSize, tint)
        if (label.isNotEmpty()) list.addText(
            font,
            ImGui.getFontSize().toInt(),
            startX + iconWidth + gap,
            y + (frameHeight - ImGui.getFontSize()) / 2f,
            tint,
            label
        )
        if (tooltipText != null && hovered) hint(tooltipText)
        return pressed
    }

    fun iconFor(label: String): Icon? {
        val text = label.lowercase().substringBefore("##")
        return when {
            text.startsWith("add keyframe") -> Icon.KEYFRAME_ADD
            text.startsWith("previous") -> Icon.CHEVRON_LEFT
            text.startsWith("next") -> Icon.CHEVRON_RIGHT
            text.startsWith("delete") || text.startsWith("clear path") || text.startsWith("remove") -> Icon.TRASH
            text.startsWith("save") -> Icon.SAVE
            text.startsWith("start export") || text == "export" || text.startsWith("export ") -> Icon.EXPORT
            text.startsWith("download") -> Icon.ARROW_DOWN
            text == "play" || text.startsWith("play ") -> Icon.PLAY
            text.startsWith("go to") || text.startsWith("jump") -> Icon.TARGET
            text.startsWith("refresh") || text.startsWith("rebuild") || text.startsWith("update from") -> Icon.REFRESH
            text.startsWith("screenshot") -> Icon.IMAGE
            text.startsWith("open") -> Icon.FOLDER
            text.startsWith("start recording") -> Icon.RECORD
            text.startsWith("stop") -> Icon.STOP
            text.startsWith("cancel") || text.startsWith("dismiss") || text.startsWith("clear selection") -> Icon.CLOSE
            text.startsWith("frame") -> Icon.FIT
            text.startsWith("look through") || text.startsWith("preview") -> Icon.EYE
            text.startsWith("approve") || text.startsWith("apply") -> Icon.CHECK
            text.startsWith("clip from") || text.startsWith("around playhead") || text.startsWith("trim") -> Icon.SCISSORS
            text.startsWith("snap") -> Icon.MAGNET
            text.startsWith("locate") || text.startsWith("search") || text.startsWith("nearest") -> Icon.SEARCH
            text.startsWith("draft") -> Icon.GAUGE
            text.startsWith("all clips") || text.startsWith("first clip") || text.startsWith("bake") -> Icon.FILM
            text.startsWith("pov proxy") -> Icon.PERSON
            text.startsWith("select all keyframes") -> Icon.KEYFRAME
            text.startsWith("new project") || text.startsWith("add") || text.startsWith("create") -> Icon.PLUS
            text.startsWith("set in/out") -> Icon.MARK_IN
            text.startsWith("rename") -> Icon.EDIT
            text.startsWith("compact") -> Icon.COMPRESS
            text.startsWith("record") -> Icon.RECORD
            text.startsWith("load") -> Icon.FOLDER
            text.startsWith("copy") -> Icon.COPY
            text.startsWith("paste") -> Icon.PASTE
            text.startsWith("undo") -> Icon.UNDO
            text.startsWith("redo") -> Icon.REDO
            text.startsWith("settings") -> Icon.SETTINGS
            text.startsWith("library") -> Icon.LIST
            text.startsWith("set thumbnail") -> Icon.IMAGE
            text.startsWith("fly") -> Icon.CAMERA
            text.startsWith("first person") -> Icon.EYE
            text.startsWith("orbit") -> Icon.ORBIT
            text.startsWith("follow") -> Icon.FOLLOW
            text.startsWith("chase") -> Icon.TARGET
            text.startsWith("hide") -> Icon.EYE_OFF
            text.startsWith("show") -> Icon.EYE
            else -> null
        }
    }

    fun iconText(icon: Icon, text: String, color: Int = EditorTheme.TEXT.u32, iconColor: Int = color) {
        val size = ImGui.getFontSize().toFloat()
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val list = ImGui.getWindowDrawList()
        Icons.draw(list, icon, x, y + (ImGui.getTextLineHeight() - size) / 2f, size, iconColor)
        list.addText(x + size + EditorFonts.px(6f), y, color, text)
        ImGui.dummy(size + EditorFonts.px(6f) + textWidth(text), ImGui.getTextLineHeight())
    }

    fun inlineDivider(height: Float = ImGui.getTextLineHeight()) {
        ImGui.sameLine()
        val x = ImGui.getCursorScreenPosX() + EditorFonts.px(6f)
        val y = ImGui.getCursorScreenPosY()
        val inset = height * 0.2f
        ImGui.getWindowDrawList().addLine(x, y + inset, x, y + height - inset, EditorTheme.SEPARATOR.u32, 1f)
        ImGui.dummy(EditorFonts.px(12f), height)
        ImGui.sameLine()
    }

    fun rangeText(start: String, end: String, color: Int = EditorTheme.TEXT_MUTED.u32) {
        ImGui.pushStyleColor(ImGuiCol.Text, color)
        ImGui.textUnformatted(start)
        ImGui.popStyleColor()
        ImGui.sameLine()
        val size = ImGui.getFontSize() * 0.8f
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY() + (ImGui.getTextLineHeight() - size) / 2f
        Icons.draw(ImGui.getWindowDrawList(), Icon.ARROW_RIGHT, x, y, size, color)
        ImGui.dummy(size, ImGui.getTextLineHeight())
        ImGui.sameLine()
        ImGui.pushStyleColor(ImGuiCol.Text, color)
        ImGui.textUnformatted(end)
        ImGui.popStyleColor()
    }

    fun hintLineWidth(parts: List<String>): Float {
        var width = 0f
        for ((index, part) in parts.withIndex()) {
            if (index > 0) width += EditorFonts.px(14f)
            width += KeyCaps.mixedWidth(part)
        }
        return width
    }

    fun hintLine(parts: List<String>, color: Int = EditorTheme.TEXT_DIM.u32, keyColor: Int = EditorTheme.TEXT_MUTED.u32) {
        if (parts.isEmpty()) {
            ImGui.dummy(1f, ImGui.getTextLineHeight())
            return
        }
        for ((index, part) in parts.withIndex()) {
            if (index > 0) ImGui.sameLine(0f, EditorFonts.px(14f))
            KeyCaps.mixed(part, color = keyColor, textColor = color)
        }
    }

    fun textWidth(text: String): Float {
        ImGui.calcTextSize(measure, text)
        return measure.x
    }

    fun clip(text: String, width: Float): String {
        if (textWidth(text) <= width) return text
        var end = text.length
        while (end > 1 && textWidth(text.substring(0, end) + "...") > width) end--
        return text.substring(0, end) + "..."
    }

    fun row(
        id: String,
        height: Float,
        selected: Boolean,
        content: (x: Float, y: Float, width: Float, hovered: Boolean) -> Unit
    ): Boolean {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val width = ImGui.getContentRegionAvailX()
        ImGui.setNextItemAllowOverlap()
        val pressed = ImGui.invisibleButton(id, maxOf(1f, width), height)
        val hovered = ImGui.isItemHovered()
        val list = ImGui.getWindowDrawList()
        if (selected) list.addRectFilled(x, y, x + width, y + height, EditorTheme.SELECTION_FILL.u32, EditorFonts.px(5f))
        else if (hovered) list.addRectFilled(x, y, x + width, y + height, EditorTheme.CONTROL.u32, EditorFonts.px(5f))
        content(x, y, width, hovered)
        return pressed
    }

    fun cursorHand() = ImGui.setMouseCursor(ImGuiMouseCursor.Hand)

    fun clicked(button: Int = ImGuiMouseButton.Left): Boolean = ImGui.isItemClicked(button)

    private val AXIS_COLOURS = listOf(EditorTheme.AXIS_X, EditorTheme.AXIS_Y, EditorTheme.AXIS_Z)

    private val groupWidths = HashMap<Int, Float>()

    val BUTTON_ICON_GAP: Float get() = EditorFonts.px(6f)
}
