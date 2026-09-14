package gg.sona.recast.editor.imgui

import gg.sona.recast.clip.export.ExportHandle
import gg.sona.recast.clip.export.ExportState
import gg.sona.recast.core.time.Nanos
import gg.sona.recast.render.ExportLive
import gg.sona.recast.render.ExportSettings
import gg.sona.recast.render.QualityMode
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiWindowFlags

class ExportView(private val context: EditorContext) {

    private var smoothedFps = 0.0
    private var lastFrames = -1L
    private var lastSampleNanos = 0L

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
        if (ImGui.begin("##recast-export", flags)) {
            card(frame, live, handle)
        }
        ImGui.end()
        ImGui.popStyleColor()
        ImGui.popStyleVar(3)
    }

    private fun card(frame: FrameContext, live: ExportLive?, handle: ExportHandle?) {
        val settings = live?.settings
        val cardWidth = minOf(frame.displayWidth - EditorFonts.px(48f), EditorFonts.px(760f))
        val pad = EditorFonts.px(24f)
        val previewHeight =
            if (settings != null) ((cardWidth - pad * 2) * settings.height / settings.width.coerceAtLeast(1)).coerceAtMost(
                frame.displayHeight * 0.45f
            ) else 0f
        val cardHeight = previewHeight + EditorFonts.px(236f)
        val x = (frame.displayWidth - cardWidth) / 2f
        val y = ((frame.displayHeight - cardHeight) / 2f).coerceAtLeast(EditorFonts.px(16f))
        val list = ImGui.getWindowDrawList()
        list.addRectFilled(x, y, x + cardWidth, y + cardHeight, EditorTheme.PANEL.u32, EditorFonts.px(10f))
        list.addRect(x, y, x + cardWidth, y + cardHeight, EditorTheme.BORDER_SOFT.u32, EditorFonts.px(10f))
        ImGui.setCursorPos(x + pad, y + pad)
        ImGui.beginGroup()
        val name = handle?.job?.name?.removePrefix("export ") ?: "Export"
        val cancelling = handle?.detail?.startsWith("cancel") == true
        EditorFonts.with(EditorFonts.heading) {
            ImGui.pushStyleColor(ImGuiCol.Text, EditorTheme.TEXT.u32)
            ImGui.textUnformatted(if (cancelling) "Cancelling $name" else "Exporting $name")
            ImGui.popStyleColor()
        }
        if (settings != null) Widgets.smallText(summary(settings), EditorTheme.TEXT_MUTED.u32)
        ImGui.dummy(0f, EditorFonts.px(8f))
        if (live != null && live.previewTexture != 0 && previewHeight > 0f) {
            val previewWidth = cardWidth - pad * 2
            val px = ImGui.getCursorScreenPosX()
            val py = ImGui.getCursorScreenPosY()
            list.addRectFilled(
                px,
                py,
                px + previewWidth,
                py + previewHeight,
                EditorTheme.PANEL_SUNKEN.u32,
                EditorFonts.px(6f)
            )
            val aspect = live.previewWidth.toFloat() / live.previewHeight.coerceAtLeast(1)
            var drawWidth = previewWidth
            var drawHeight = previewWidth / aspect
            if (drawHeight > previewHeight) {
                drawHeight = previewHeight
                drawWidth = previewHeight * aspect
            }
            val offsetX = (previewWidth - drawWidth) / 2f
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
                EditorFonts.px(4f)
            )
            list.addRect(px, py, px + previewWidth, py + previewHeight, EditorTheme.BORDER_SOFT.u32, EditorFonts.px(6f))
            ImGui.dummy(previewWidth, previewHeight)
            ImGui.dummy(0f, EditorFonts.px(10f))
        }
        val progress = handle?.progress?.toFloat() ?: 0f
        val barWidth = cardWidth - pad * 2
        val bx = ImGui.getCursorScreenPosX()
        val by = ImGui.getCursorScreenPosY()
        val barHeight = EditorFonts.px(10f)
        list.addRectFilled(bx, by, bx + barWidth, by + barHeight, EditorTheme.CONTROL.u32, barHeight / 2f)
        if (progress > 0f) {
            val color = if (cancelling) EditorTheme.WARNING else EditorTheme.ACCENT_TEXT
            list.addRectFilled(
                bx,
                by,
                bx + barWidth * progress.coerceIn(0f, 1f),
                by + barHeight,
                color.u32,
                barHeight / 2f
            )
        }
        ImGui.dummy(barWidth, barHeight)
        ImGui.dummy(0f, EditorFonts.px(6f))
        stats(frame, live, handle, barWidth)
        ImGui.dummy(0f, EditorFonts.px(10f))
        val waiting = live?.stage?.takeIf { it.isNotBlank() }
        val stage = waiting ?: handle?.detail?.takeIf { it.isNotBlank() } ?: "starting"
        Widgets.smallText(stage, if (waiting != null) EditorTheme.WARNING.u32 else EditorTheme.TEXT_MUTED.u32)
        ImGui.dummy(0f, EditorFonts.px(8f))
        ImGui.setCursorPosX(x + cardWidth - pad - Widgets.buttonWidth("Cancel export  (Esc)", Widgets.ButtonStyle.DANGER))
        if (cancelling) ImGui.beginDisabled()
        if (Widgets.dangerButton("Cancel export  (Esc)")) handle?.cancel()
        if (cancelling) ImGui.endDisabled()
        ImGui.endGroup()
        val queued = context.exports?.queue()?.handles()?.count { it.state == ExportState.QUEUED } ?: 0
        if (queued > 0) {
            ImGui.setCursorPos(x + pad, y + cardHeight + EditorFonts.px(10f))
            Widgets.smallText("$queued more export${if (queued == 1) "" else "s"} queued", EditorTheme.TEXT_DIM.u32)
        }
    }

    private fun stats(frame: FrameContext, live: ExportLive?, handle: ExportHandle?, width: Float) {
        val framesDone = live?.framesDone ?: 0L
        val frameCount = live?.frameCount ?: 0L
        val started = handle?.startedAtNanos?.takeIf { it > 0L } ?: live?.startedAtNanos ?: frame.nowNanos
        val elapsed = (frame.nowNanos - started).coerceAtLeast(0L)
        if (framesDone != lastFrames) {
            if (lastFrames >= 0 && frame.nowNanos > lastSampleNanos) {
                val instant =
                    (framesDone - lastFrames) * Nanos.PER_SECOND.toDouble() / (frame.nowNanos - lastSampleNanos)
                smoothedFps = if (smoothedFps == 0.0) instant else smoothedFps + (instant - smoothedFps) * 0.2
            }
            lastFrames = framesDone
            lastSampleNanos = frame.nowNanos
        }
        val remaining =
            if (smoothedFps > 0.0 && frameCount > framesDone) ((frameCount - framesDone) / smoothedFps * Nanos.PER_SECOND).toLong() else -1L
        val column = width / 4f
        stat("Frame", if (frameCount > 0) "$framesDone / $frameCount" else "$framesDone", column)
        ImGui.sameLine()
        stat("Speed", if (smoothedFps > 0.0) String.format("%.1f fps", smoothedFps) else "-", column)
        ImGui.sameLine()
        stat("Elapsed", clock(elapsed), column)
        ImGui.sameLine()
        stat("Remaining", if (remaining >= 0L) clock(remaining) else "-", column)
    }

    private fun stat(label: String, value: String, width: Float) {
        ImGui.beginGroup()
        Widgets.smallText(label.uppercase(), EditorTheme.TEXT_DIM.u32)
        EditorFonts.with(EditorFonts.bodyMedium) {
            ImGui.pushStyleColor(ImGuiCol.Text, EditorTheme.TEXT.u32)
            ImGui.textUnformatted(value)
            ImGui.popStyleColor()
        }
        ImGui.dummy(width - EditorFonts.px(8f), 0f)
        ImGui.endGroup()
    }

    private fun clock(nanos: Long): String {
        val total = nanos / Nanos.PER_SECOND
        val hours = total / 3600
        val minutes = (total / 60) % 60
        val seconds = total % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format(
            "%d:%02d",
            minutes,
            seconds
        )
    }

    private fun summary(settings: ExportSettings): String {
        val parts = ArrayList<String>()
        parts += "${settings.width}x${settings.height}"
        parts += "${settings.fps} fps"
        parts += settings.format.label
        if (settings.format.needsFfmpeg && settings.format.supportsBitrate) {
            parts += if (settings.qualityMode == QualityMode.CRF) "CRF ${settings.crf}" else "${settings.bitrateKbps / 1000} Mbps"
        }
        if (settings.supersample > 1) parts += "${settings.supersample}x supersampled"
        if (settings.motionBlur.enabled) parts += "motion blur ${settings.motionBlur.samples}x"
        if (settings.gameAudio) parts += "game audio"
        parts += clock(settings.outputDurationNanos)
        return parts.joinToString("  |  ")
    }
}
