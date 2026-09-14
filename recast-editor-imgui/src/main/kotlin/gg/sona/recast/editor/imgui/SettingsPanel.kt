package gg.sona.recast.editor.imgui

import gg.sona.recast.clip.export.ExportState
import imgui.ImGui
import java.nio.file.Path

class SettingsPanel(private val context: EditorContext) : DialogPanel("Settings", Icon.SETTINGS, 780f, 540f, SECTIONS) {

    override fun content(frame: FrameContext) {
        when (dialog.section) {
            0 -> recording()
            1 -> editor()
            2 -> replay()
            3 -> freeCamera()
            4 -> export()
            else -> folders()
        }
    }

    private fun recording() {
        val recording = context.host.recording
        if (Widgets.beginProperties("settings-recording")) {
            Widgets.property("Auto-record", "Start recording automatically whenever you join a world or server")
            Widgets.toggle("##auto", recording.autoRecord)?.let { recording.autoRecord = it }
            Widgets.property("Keyframe every", "Snapshots make seeking faster at the cost of file size")
            Widgets.intDrag("##interval", recording.keyframeIntervalSeconds, 0.5f, 5, 60, "%d s")
                ?.let { recording.keyframeIntervalSeconds = it }
            Widgets.property(
                "REC indicator",
                "Small recording badge in the top-left corner of the game while recording"
            )
            Widgets.toggle("##indicator", recording.showIndicator)?.let { recording.showIndicator = it }
            Widgets.property(
                "Instant replay",
                "How much to keep when you press the instant clip key (F9 by default), like ShadowPlay"
            )
            Widgets.intDrag("##instant", recording.instantClipSeconds, 1f, 5, 300, "last %d s")
                ?.let { recording.instantClipSeconds = it }
            Widgets.endProperties()
        }
    }

    private fun editor() {
        val host = context.host
        val ui = context.ui
        Widgets.header("Interface")
        if (Widgets.beginProperties("settings-interface")) {
            Widgets.property("UI scale", "Rescales fonts and spacing; RECAST_UI_SCALE overrides the DPI default")
            val current = SCALES.indexOfFirst { Math.abs(it - host.uiScale) < 0.01f }
            Widgets.segmented("uiscale", SCALES.map { "${(it * 100).toInt()}%" }, current, 0f)
                ?.let { host.uiScale = SCALES[it] }
            Widgets.property("Autosave", "Save the project a few seconds after every change")
            Widgets.toggle("##autosave", ui.autosave)?.let { ui.autosave = it }
            Widgets.property("Loop in/out", "Playback jumps back to the in point when it reaches the out point")
            Widgets.toggle("##loop", ui.loopPlayback)?.let { ui.loopPlayback = it }
            Widgets.endProperties()
        }
        Widgets.header("Scene")
        if (Widgets.beginProperties("settings-scene")) {
            Widgets.property("Scene status", "Camera mode and path state in the top left of the scene")
            Widgets.toggle("##overlay", ui.viewportOverlay)?.let { ui.viewportOverlay = it }
            Widgets.property("Entity outlines", "Corner box around the hovered and selected entity")
            Widgets.toggle("##boxes", ui.entityBoxes)?.let { ui.entityBoxes = it }
            Widgets.property("Keyframe labels", "Time labels next to hovered and selected keyframes")
            Widgets.toggle("##kflabels", ui.keyframeLabels)?.let { ui.keyframeLabels = it }
            Widgets.property("Orientation gizmo", "Axis gizmo in the top right of the scene")
            Widgets.toggle("##orientation", ui.orientationGizmo)?.let { ui.orientationGizmo = it }
            Widgets.endProperties()
        }
    }

    private fun replay() {
        val host = context.host
        if (Widgets.beginProperties("settings-replay")) {
            Widgets.property(
                "Render what was recorded",
                "Raise the render distance while replaying so every chunk the recording loaded is drawn (up to 16). Your own setting comes back when the replay closes."
            )
            val matchKey = "replay.matchRecordedViewDistance"
            Widgets.toggle("##matchvd", host.preference(matchKey)?.toBooleanStrictOrNull() ?: true)
                ?.let { host.setPreference(matchKey, it.toString()) }
            Widgets.property(
                "Server resource packs",
                "Downloads and applies the resource pack the server sent while recording, so the replay uses the same textures and sounds. Cached in server-resource-packs like vanilla."
            )
            val packKey = "replay.serverResourcePacks"
            Widgets.toggle("##svpacks", host.preference(packKey)?.toBooleanStrictOrNull() ?: true)
                ?.let { host.setPreference(packKey, it.toString()) }
            Widgets.endProperties()
        }
    }

    private fun freeCamera() {
        val settings = context.host.camera.settings
        if (Widgets.beginProperties("settings-camera")) {
            Widgets.property("Fly speed")
            Widgets.doubleSlider("##speed", settings.freeSpeed, 0.5, 100.0, "%.1f b/s")?.let { settings.freeSpeed = it }
            Widgets.property("Sensitivity")
            Widgets.doubleSlider("##sens", settings.freeSensitivity, 0.02, 1.0, "%.2f")
                ?.let { settings.freeSensitivity = it }
            Widgets.property("Acceleration", "Speed ramps up the longer you hold a movement key")
            Widgets.toggle("##accel", settings.freeAcceleration)?.let { settings.freeAcceleration = it }
            Widgets.property("Easing", "Smooth starts and stops")
            Widgets.toggle("##easing", settings.freeEasing)?.let { settings.freeEasing = it }
            Widgets.endProperties()
        }
    }

    private fun export() {
        val backend = context.exports
        if (Widgets.beginProperties("settings-export")) {
            Widgets.property("Open folder", "Open the export folder when a render finishes")
            Widgets.toggle("##openfolder", context.ui.openFolderAfterExport)
                ?.let { context.ui.openFolderAfterExport = it }
            if (backend != null) {
                Widgets.property("ffmpeg")
                if (backend.ffmpegAvailable) {
                    Widgets.smallText(backend.ffmpegVersion ?: "found", EditorTheme.TEXT_DIM.u32, clipToWidth = true)
                    Widgets.tooltip(backend.ffmpegPath)
                } else {
                    val download = backend.queue().handles()
                        .filter { it.job.name == "download ffmpeg" }
                        .maxByOrNull { it.startedAtNanos }
                    when (download?.state) {
                        ExportState.QUEUED, ExportState.RUNNING -> {
                            val fraction = download.progress.toFloat()
                            Widgets.progress(fraction, -1f, download.detail.ifBlank { "${(fraction * 100).toInt()}%" })
                        }

                        ExportState.FAILED -> {
                            Widgets.pill("download failed", EditorTheme.RECORD)
                            ImGui.sameLine()
                            if (Widgets.accentButton("Retry")) backend.downloadFfmpeg()
                            Widgets.tooltip(download.failure?.message ?: "Unknown error")
                        }

                        else -> {
                            Widgets.pill("not installed", EditorTheme.WARNING)
                            ImGui.sameLine()
                            if (backend.ffmpegDownloadSupported && Widgets.accentButton("Download")) backend.downloadFfmpeg()
                        }
                    }
                }
                val hardware =
                    backend.encoders().filter { it.endsWith("_nvenc") || it.endsWith("_amf") || it.endsWith("_qsv") }
                if (hardware.isNotEmpty()) {
                    Widgets.property("Hardware encoders")
                    Widgets.smallText(hardware.joinToString(", "), EditorTheme.TEXT_DIM.u32, clipToWidth = true)
                }
            }
            Widgets.endProperties()
        }
    }

    private fun folders() {
        if (Widgets.beginProperties("settings-folders")) {
            folder(
                "Recordings",
                context.host.replay.listRecordings().firstOrNull()?.parent
                    ?: context.host.projectsDirectory.resolveSibling("recordings")
            )
            folder("Projects", context.host.projectsDirectory)
            folder("Exports", context.host.exportsDirectory)
            folder("Clips", context.host.bakesDirectory)
            Widgets.endProperties()
        }
    }

    private fun folder(label: String, path: Path) {
        Widgets.property(label)
        if (Widgets.iconButton(
                "open-$label",
                Icon.FOLDER,
                ImGui.getFrameHeight(),
                path.toString()
            )
        ) runCatching {
            java.awt.Desktop.getDesktop().open(path.toFile())
        }.onFailure { context.status("Could not open folder: ${it.message}") }
        ImGui.sameLine()
        ImGui.alignTextToFramePadding()
        Widgets.smallText(path.toString(), EditorTheme.TEXT_DIM.u32, clipToWidth = true)
    }

    private companion object {
        val SCALES = floatArrayOf(0.85f, 1f, 1.15f, 1.25f, 1.5f, 1.75f, 2f)
        val SECTIONS = listOf(
            Dialog.Section("Recording", Icon.RECORD),
            Dialog.Section("Editor", Icon.SLIDERS),
            Dialog.Section("Replay", Icon.FILM),
            Dialog.Section("Free camera", Icon.CAMERA),
            Dialog.Section("Export", Icon.EXPORT),
            Dialog.Section("Folders", Icon.FOLDER),
        )
    }
}
