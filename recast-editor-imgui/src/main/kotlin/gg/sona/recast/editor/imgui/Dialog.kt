package gg.sona.recast.editor.imgui

import imgui.ImGui
import imgui.flag.*
import imgui.type.ImBoolean

class Dialog(val title: String, val icon: Icon, private val width: Float, private val height: Float) {

    class Section(val title: String, val icon: Icon, val badge: String? = null)

    var sections: List<Section> = emptyList()
    var section: Int = 0
    var subtitle: String = ""

    private val id = "$title##dialog"

    fun draw(open: ImBoolean, content: () -> Unit, footer: (() -> Unit)? = null) {
        val popupOpen = ImGui.isPopupOpen(id)
        if (!open.get() && !popupOpen) return
        if (open.get() && !popupOpen) ImGui.openPopup(id)
        val viewport = ImGui.getMainViewport()
        val w = minOf(EditorFonts.px(width), viewport.workSizeX - EditorFonts.px(32f))
        val h = minOf(EditorFonts.px(height), viewport.workSizeY - EditorFonts.px(32f))
        ImGui.setNextWindowPos(
            viewport.workPosX + viewport.workSizeX / 2f,
            viewport.workPosY + viewport.workSizeY / 2f,
            ImGuiCond.Always,
            0.5f,
            0.5f
        )
        ImGui.setNextWindowSize(w, h, ImGuiCond.Always)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, ROUNDING)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 1f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)
        ImGui.pushStyleColor(ImGuiCol.PopupBg, EditorTheme.PANEL.u32)
        ImGui.pushStyleColor(ImGuiCol.Border, EditorTheme.BORDER_SOFT.u32)
        val flags =
            ImGuiWindowFlags.NoTitleBar or ImGuiWindowFlags.NoResize or ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoScrollbar or
                    ImGuiWindowFlags.NoScrollWithMouse or ImGuiWindowFlags.NoDocking or ImGuiWindowFlags.NoSavedSettings
        val shown = ImGui.beginPopupModal(id, flags)
        ImGui.popStyleColor(2)
        ImGui.popStyleVar(3)
        if (!shown) return
        try {
            if (!open.get() || escapePressed()) {
                open.set(false)
                ImGui.closeCurrentPopup()
                return
            }
            frame(open, w, h, content, footer)
        } finally {
            ImGui.endPopup()
        }
    }

    private fun escapePressed(): Boolean =
        ImGui.isKeyPressed(ImGuiKey.Escape, false) && !ImGui.getIO().wantTextInput && ImGui.isWindowFocused(
            ImGuiFocusedFlags.RootAndChildWindows or ImGuiFocusedFlags.NoPopupHierarchy
        )

    private fun frame(open: ImBoolean, w: Float, h: Float, content: () -> Unit, footer: (() -> Unit)?) {
        val x0 = ImGui.getWindowPosX()
        val y0 = ImGui.getWindowPosY()
        val list = ImGui.getWindowDrawList()
        val sidebarWidth = if (sections.isEmpty()) 0f else SIDEBAR_WIDTH
        val footerHeight = if (footer != null) FOOTER_HEIGHT else 0f
        val pad = EditorFonts.px(20f)
        if (sidebarWidth > 0f) {
            list.addRectFilled(
                x0,
                y0,
                x0 + sidebarWidth,
                y0 + h,
                EditorTheme.APP_BG.u32,
                ROUNDING,
                ImDrawFlags.RoundCornersLeft
            )
            list.addLine(x0 + sidebarWidth, y0, x0 + sidebarWidth, y0 + h, EditorTheme.SEPARATOR.u32, 1f)
            sidebar(sidebarWidth, h)
        }
        val heading = if (sections.isEmpty()) title else sections[section.coerceIn(0, sections.size - 1)].title
        ImGui.setCursorPos(sidebarWidth + pad, EditorFonts.px(18f))
        if (sections.isEmpty()) {
            Icons.inline(icon, EditorFonts.px(18f), EditorTheme.TEXT_MUTED.u32)
            ImGui.sameLine(0f, EditorFonts.px(8f))
        }
        EditorFonts.with(EditorFonts.heading) { ImGui.textUnformatted(heading) }
        if (subtitle.isNotEmpty()) {
            ImGui.sameLine(0f, EditorFonts.px(10f))
            ImGui.setCursorPosY(ImGui.getCursorPosY() + EditorFonts.px(3f))
            Widgets.smallText(subtitle, EditorTheme.TEXT_DIM.u32)
        }
        val close = EditorFonts.px(26f)
        ImGui.setCursorPos(w - EditorFonts.px(14f) - close, EditorFonts.px(14f))
        if (Widgets.iconButton(
                "dialog-close",
                Icon.CLOSE,
                close,
                "Close  Esc",
                color = EditorTheme.TEXT_MUTED.u32,
                iconScale = 0.5f
            )
        ) {
            open.set(false)
            ImGui.closeCurrentPopup()
            return
        }
        val contentTop = HEADER_HEIGHT
        val contentHeight = h - contentTop - footerHeight - (if (footer != null) 0f else EditorFonts.px(16f))
        ImGui.setCursorPos(sidebarWidth + pad, contentTop)
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, EditorFonts.px(6f), EditorFonts.px(6f))
        val contentShown = ImGui.beginChild(
            "##dialog-content",
            w - sidebarWidth - pad * 2f,
            contentHeight,
            false,
            ImGuiWindowFlags.None
        )
        try {
            if (contentShown) content()
        } finally {
            ImGui.endChild()
            ImGui.popStyleVar()
        }
        if (footer != null) {
            val fy = y0 + h - footerHeight
            list.addLine(x0 + sidebarWidth, fy, x0 + w, fy, EditorTheme.SEPARATOR.u32, 1f)
            ImGui.setCursorPos(sidebarWidth + pad, h - footerHeight)
            val footerShown = ImGui.beginChild(
                "##dialog-footer",
                w - sidebarWidth - pad * 2f,
                footerHeight,
                false,
                ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse
            )
            try {
                if (footerShown) {
                    ImGui.setCursorPosY((footerHeight - ImGui.getFrameHeight()) / 2f)
                    footer()
                }
            } finally {
                ImGui.endChild()
            }
        }
    }

    private fun sidebar(width: Float, height: Float) {
        val inset = EditorFonts.px(12f)
        ImGui.setCursorPos(inset, EditorFonts.px(18f))
        val shown = ImGui.beginChild(
            "##dialog-sidebar",
            width - inset * 2f,
            height - EditorFonts.px(30f),
            false,
            ImGuiWindowFlags.NoScrollbar
        )
        try {
            if (!shown) return
            ImGui.setCursorPosX(EditorFonts.px(8f))
            Icons.inline(icon, EditorFonts.px(18f), EditorTheme.TEXT_MUTED.u32)
            ImGui.sameLine(0f, EditorFonts.px(8f))
            EditorFonts.with(EditorFonts.heading) { ImGui.textUnformatted(title) }
            ImGui.dummy(0f, EditorFonts.px(10f))
            val rowHeight = EditorFonts.px(28f)
            val list = ImGui.getWindowDrawList()
            for ((index, entry) in sections.withIndex()) {
                val selected = index == section
                val x = ImGui.getCursorScreenPosX()
                val y = ImGui.getCursorScreenPosY()
                val rowWidth = ImGui.getContentRegionAvailX()
                val pressed = ImGui.invisibleButton("section-$index", maxOf(1f, rowWidth), rowHeight)
                val hovered = ImGui.isItemHovered()
                if (selected) list.addRectFilled(
                    x,
                    y,
                    x + rowWidth,
                    y + rowHeight,
                    EditorTheme.ACCENT.u32,
                    EditorFonts.px(6f)
                )
                else if (hovered) list.addRectFilled(
                    x,
                    y,
                    x + rowWidth,
                    y + rowHeight,
                    EditorTheme.TEXT.u32(0.06f),
                    EditorFonts.px(6f)
                )
                val iconSize = EditorFonts.px(15f)
                val tint = if (selected) 0xFFFFFFFF.toInt() else EditorTheme.TEXT_MUTED.u32
                Icons.draw(list, entry.icon, x + EditorFonts.px(9f), y + (rowHeight - iconSize) / 2f, iconSize, tint)
                val font = if (selected) EditorFonts.bodyMedium else EditorFonts.body
                list.addText(
                    font,
                    ImGui.getFontSize(),
                    x + EditorFonts.px(32f),
                    y + (rowHeight - ImGui.getFontSize()) / 2f,
                    if (selected) 0xFFFFFFFF.toInt() else EditorTheme.TEXT.u32,
                    entry.title
                )
                val badge = entry.badge
                if (badge != null) {
                    val badgeWidth =
                        EditorFonts.with(EditorFonts.smallMedium) { Widgets.textWidth(badge) } + EditorFonts.px(12f)
                    val badgeHeight = EditorFonts.px(17f)
                    val bx = x + rowWidth - badgeWidth - EditorFonts.px(6f)
                    val by = y + (rowHeight - badgeHeight) / 2f
                    list.addRectFilled(
                        bx,
                        by,
                        bx + badgeWidth,
                        by + badgeHeight,
                        if (selected) EditorTheme.TEXT.u32(0.22f) else EditorTheme.ACCENT.u32(0.3f),
                        badgeHeight / 2f
                    )
                    list.addText(
                        EditorFonts.smallMedium,
                        EditorFonts.smallMedium.fontSize.toInt(),
                        bx + EditorFonts.px(6f),
                        by + (badgeHeight - EditorFonts.smallMedium.fontSize) / 2f,
                        if (selected) 0xFFFFFFFF.toInt() else EditorTheme.ACCENT_TEXT.u32,
                        badge
                    )
                }
                if (pressed) section = index
                if (hovered) Widgets.cursorHand()
            }
        } finally {
            ImGui.endChild()
        }
    }

    companion object {
        val ROUNDING: Float get() = EditorFonts.px(12f)
        val SIDEBAR_WIDTH: Float get() = EditorFonts.px(184f)
        val HEADER_HEIGHT: Float get() = EditorFonts.px(54f)
        val FOOTER_HEIGHT: Float get() = EditorFonts.px(58f)

        fun rightAlign(vararg widths: Float, spacing: Float = EditorFonts.px(8f)) {
            val total = widths.sum() + spacing * (widths.size - 1).coerceAtLeast(0)
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (ImGui.getContentRegionAvailX() - total).coerceAtLeast(0f))
        }

        fun confirm(
            title: String,
            body: String,
            confirmLabel: String,
            danger: Boolean = true,
            onConfirm: () -> Unit,
            onCancel: () -> Unit
        ) {
            val id = "$title##confirm"
            if (!ImGui.isPopupOpen(id)) ImGui.openPopup(id)
            val viewport = ImGui.getMainViewport()
            ImGui.setNextWindowPos(
                viewport.workPosX + viewport.workSizeX / 2f,
                viewport.workPosY + viewport.workSizeY / 2f,
                ImGuiCond.Always,
                0.5f,
                0.5f
            )
            ImGui.setNextWindowSize(EditorFonts.px(400f), 0f, ImGuiCond.Always)
            ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, ROUNDING)
            ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 1f)
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, EditorFonts.px(22f), EditorFonts.px(20f))
            ImGui.pushStyleColor(ImGuiCol.PopupBg, EditorTheme.PANEL.u32)
            ImGui.pushStyleColor(ImGuiCol.Border, EditorTheme.BORDER_SOFT.u32)
            val shown = ImGui.beginPopupModal(
                id,
                ImGuiWindowFlags.NoTitleBar or ImGuiWindowFlags.AlwaysAutoResize or ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoDocking
            )
            ImGui.popStyleColor(2)
            ImGui.popStyleVar(3)
            if (!shown) return
            try {
                EditorFonts.with(EditorFonts.heading) { ImGui.textUnformatted(title) }
                ImGui.dummy(0f, EditorFonts.px(2f))
                ImGui.pushTextWrapPos(EditorFonts.px(356f))
                Widgets.mutedText(body)
                ImGui.popTextWrapPos()
                ImGui.dummy(0f, EditorFonts.px(12f))
                val buttonWidth = EditorFonts.px(110f)
                rightAlign(buttonWidth, buttonWidth)
                val escape = ImGui.isKeyPressed(
                    ImGuiKey.Escape,
                    false
                ) && ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows or ImGuiFocusedFlags.NoPopupHierarchy)
                if (Widgets.button("Cancel", buttonWidth) || escape) {
                    onCancel()
                    ImGui.closeCurrentPopup()
                }
                ImGui.sameLine(0f, EditorFonts.px(8f))
                val enter = ImGui.isKeyPressed(ImGuiKey.Enter, false) && !ImGui.getIO().wantTextInput
                if ((if (danger) Widgets.dangerButton(confirmLabel, buttonWidth) else Widgets.accentButton(
                        confirmLabel,
                        buttonWidth
                    )) || enter
                ) {
                    onConfirm()
                    ImGui.closeCurrentPopup()
                }
            } finally {
                ImGui.endPopup()
            }
        }
    }
}
