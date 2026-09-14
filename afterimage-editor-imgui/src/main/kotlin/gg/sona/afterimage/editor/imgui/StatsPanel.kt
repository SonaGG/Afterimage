package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.core.time.Nanos
import imgui.ImGui
import imgui.flag.ImGuiWindowFlags

class StatsPanel(private val context: EditorContext) : DialogPanel("Stats", Icon.GAUGE, 720f, 420f) {
    private var lastSampleNanos = 0L
    private var frameTimeMillis = 0.0
    private var frames = 0

    override fun content(frame: FrameContext) {
        frames++
        if (frame.nowNanos - lastSampleNanos >= Nanos.PER_SECOND / 2) {
            frameTimeMillis = (frame.nowNanos - lastSampleNanos) / 1_000_000.0 / maxOf(1, frames)
            lastSampleNanos = frame.nowNanos
            frames = 0
        }
        val replay = context.replay
        val column = (ImGui.getContentRegionAvailX() - EditorFonts.px(20f)) / 2f
        val height = ImGui.getContentRegionAvailY()
        if (ImGui.beginChild("##stats-editor", column, height, false, ImGuiWindowFlags.None)) {
            Widgets.header("Editor")
            if (Widgets.beginProperties("editor")) {
                Widgets.property("Frame time")
                Widgets.mutedText(
                    String.format(
                        "%.1f ms  (%.0f fps)",
                        frameTimeMillis,
                        if (frameTimeMillis > 0) 1000.0 / frameTimeMillis else 0.0
                    )
                )
                val runtime = Runtime.getRuntime()
                Widgets.property("Memory")
                Widgets.mutedText("${(runtime.totalMemory() - runtime.freeMemory()) shr 20} / ${runtime.maxMemory() shr 20} MB")
                Widgets.endProperties()
            }
        }
        ImGui.endChild()
        ImGui.sameLine(0f, EditorFonts.px(20f))
        if (ImGui.beginChild("##stats-replay", column, height, false, ImGuiWindowFlags.None)) {
            Widgets.header("Replay")
            if (replay == null) {
                Widgets.smallText("Open a replay to see playback statistics.", EditorTheme.TEXT_DIM.u32)
            } else {
                val shadow = replay.shadow
                if (Widgets.beginProperties("replay")) {
                    Widgets.property("Position")
                    Widgets.mutedText("${TimeFormat.clock(replay.positionNanos)} / ${TimeFormat.clock(replay.durationNanos)}")
                    Widgets.property("Speed")
                    Widgets.mutedText(
                        String.format(
                            "%.2fx  %s",
                            replay.speed,
                            if (replay.playing) "playing" else "paused"
                        )
                    )
                    Widgets.property("Packets")
                    Widgets.mutedText("${replay.packetsDelivered} delivered    ${shadow.packetsApplied} tracked")
                    Widgets.property("Seeks")
                    Widgets.mutedText(
                        "${replay.seeks}    last ${
                            String.format(
                                "%.1f",
                                replay.lastSeekDurationNanos / 1_000_000.0
                            )
                        } ms    ${replay.lastDiffPackets} diff packets"
                    )
                    Widgets.property("Chunks")
                    Widgets.mutedText("${shadow.world.chunks.size}")
                    Widgets.property("Entities")
                    Widgets.mutedText("${shadow.entities.size}    ${shadow.players.entries.size} players listed")
                    Widgets.property("Camera frames")
                    Widgets.mutedText(
                        "${shadow.localPlayer.cameraFrames.size} buffered    ${
                            String.format(
                                "%.1f",
                                shadow.localPlayer.cameraFrames.intervalEstimate() / 1_000_000.0
                            )
                        } ms interval"
                    )
                    Widgets.property("Dimension")
                    Widgets.mutedText("${shadow.world.dimension}")
                    Widgets.endProperties()
                }
            }
        }
        ImGui.endChild()
    }
}
