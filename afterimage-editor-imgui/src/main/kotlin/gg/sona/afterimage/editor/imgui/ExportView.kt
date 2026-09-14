package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.clip.export.ExportHandle
import gg.sona.afterimage.clip.export.ExportState
import gg.sona.afterimage.render.ExportLive
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiWindowFlags

class ExportView(private val context: EditorContext) {

    private val stats = ExportStats()

    fun draw(frame: FrameContext) {
        val backend = context.exports ?: return
        val live = backend.live()
        val handle = backend.queue().handles().firstOrNull { it.state == ExportState.RUNNING }
        val viewport = ImGui.getMainViewport()
        ImGui.setNextWindowPos(viewport.workPosX, viewport.workPosY, ImGuiCond.Always)
        ImGui.setNextWindowSize(viewport.workSizeX, viewport.workSizeY, ImGuiCond.Always)
        val flags =
            ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoDocking or ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoBringToFrontOnFocus or
                    ImGuiWindowFlags.NoNav or ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)
        ImGui.pushStyleColor(ImGuiCol.WindowBg, EditorTheme.APP_BG.u32)
        if (ImGui.begin("##afterimage-export", flags)) {
            content(frame, live, handle)
        }
        ImGui.end()
        ImGui.popStyleColor()
        ImGui.popStyleVar(3)
    }

    private fun content(frame: FrameContext, live: ExportLive?, handle: ExportHandle?) {
        val settings = live?.settings
        val width = minOf(frame.displayWidth - EditorFonts.px(96f), EditorFonts.px(820f))
        val previewHeight = if (settings != null) (width * settings.height / settings.width.coerceAtLeast(1)).coerceAtMost(frame.displayHeight * 0.58f) else 0f
        val titleHeight = EditorFonts.title.fontSize
        val barHeight = EditorFonts.px(4f)
        val lineHeight = ImGui.getTextLineHeight()
        val height = previewHeight + EditorFonts.px(32f) + titleHeight + EditorFonts.px(18f) + barHeight + EditorFonts.px(14f) + lineHeight + EditorFonts.px(28f) + ImGui.getFrameHeight()
        val x = ((frame.displayWidth - width) / 2f).coerceAtLeast(EditorFonts.px(24f))
        var row = ((frame.displayHeight - height) / 2f).coerceAtLeast(EditorFonts.px(24f))
        val wx = ImGui.getWindowPosX()
        val wy = ImGui.getWindowPosY()
        val list = ImGui.getWindowDrawList()

        if (previewHeight > 0f) {
            val px = wx + x
            val py = wy + row
            val radius = EditorFonts.px(8f)
            list.addRectFilled(px, py, px + width, py + previewHeight, EditorTheme.PANEL_SUNKEN.u32, radius)
            if (live != null && live.previewTexture != 0) {
                val aspect = live.previewWidth.toFloat() / live.previewHeight.coerceAtLeast(1)
                var drawWidth = width
                var drawHeight = width / aspect
                if (drawHeight > previewHeight) {
                    drawHeight = previewHeight
                    drawWidth = previewHeight * aspect
                }
                val offsetX = (width - drawWidth) / 2f
                val offsetY = (previewHeight - drawHeight) / 2f
                list.addImageRounded(
                    live.previewTexture.toLong(),
                    px + offsetX,
                    py + offsetY,
                    px + offsetX + drawWidth,
                    py + offsetY + drawHeight,
                    0f,
                    1f,
                    1f,
                    0f,
                    0xFFFFFFFF.toInt(),
                    radius
                )
            }
            row += previewHeight + EditorFonts.px(32f)
        }

        val name = handle?.job?.name?.removePrefix("export ") ?: "Export"
        val cancelling = handle?.detail?.startsWith("cancel") == true
        val sample = stats.sample(frame.nowNanos, live, handle)
        val fraction = handle?.progress?.toFloat() ?: sample.fraction
        val percent = String.format("%d%%", (fraction * 100f).toInt())
        ImGui.setCursorPos(x, row)
        EditorFonts.with(EditorFonts.title) {
            ImGui.pushStyleColor(ImGuiCol.Text, EditorTheme.TEXT.u32)
            ImGui.textUnformatted(Widgets.clip(name, width - EditorFonts.px(120f)))
            ImGui.popStyleColor()
        }
        val percentWidth = Widgets.tabularWidth(percent, EditorFonts.timecodeLarge)
        ImGui.setCursorPos(x + width - percentWidth, row + (titleHeight - EditorFonts.timecodeLarge.fontSize) / 2f)
        Widgets.tabular(percent, EditorFonts.timecodeLarge, EditorTheme.TEXT_MUTED.u32)
        row += titleHeight + EditorFonts.px(18f)

        val bx = wx + x
        val by = wy + row
        list.addRectFilled(bx, by, bx + width, by + barHeight, EditorTheme.CONTROL.u32, barHeight / 2f)
        if (fraction > 0f) {
            val fill = if (cancelling) EditorTheme.WARNING else EditorTheme.ACCENT_TEXT
            list.addRectFilled(bx, by, bx + width * fraction.coerceIn(0f, 1f), by + barHeight, fill.u32, barHeight / 2f)
        }
        row += barHeight + EditorFonts.px(14f)

        val remaining = when {
            cancelling -> "Cancelling"
            live == null -> ""
            else -> sample.remaining
        }
        if (remaining.isNotEmpty()) {
            ImGui.setCursorPos(x + width - Widgets.textWidth(remaining), row)
            Widgets.mutedText(remaining)
        }
        row += lineHeight + EditorFonts.px(28f)

        val cancel = "Cancel"
        ImGui.setCursorPos(x + width - Widgets.buttonWidth(cancel, Widgets.ButtonStyle.GHOST), row)
        if (cancelling) ImGui.beginDisabled()
        if (Widgets.ghostButton(cancel)) handle?.cancel()
        if (cancelling) ImGui.endDisabled()
        Widgets.tooltip("Stop rendering and remove the partial file  Esc")
    }
}
