package gg.sona.recast.editor.imgui

import gg.sona.recast.camera.CameraMode
import gg.sona.recast.camera.CameraSettings
import gg.sona.recast.clip.export.ExportHandle
import gg.sona.recast.clip.export.ExportState
import gg.sona.recast.core.time.Nanos
import gg.sona.recast.editor.EditorSession
import gg.sona.recast.editor.LaneKind
import gg.sona.recast.editor.commands.SetLaneState
import gg.sona.recast.render.*
import imgui.ImGui
import imgui.flag.ImGuiInputTextFlags
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiTableColumnFlags
import imgui.flag.ImGuiTableFlags
import imgui.type.ImString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

// TODO: the layout in here is sort of cooked
//       but i cba to make it nicer since we'll get a UI rewrite eventually
class ExportPanel(private val context: EditorContext) :
    DialogPanel("Export", Icon.EXPORT, 900f, 600f, SECTIONS, hasFooter = true) {

    private enum class Range(val label: String) { IN_OUT("In to out"), WHOLE("Whole replay"), CLIP("Selected clip") }

    private class Size(val label: String, val width: Int, val height: Int)

    private var format = ExportFormat.MP4_H264
    private var sizeIndex = 1
    private var width = 1920
    private var height = 1080
    private var fps = 60
    private var range = Range.IN_OUT
    private var qualityMode = QualityMode.CRF
    private var crf = 18
    private var bitrateMbps = 20.0
    private var targetMegabytes = 25.0
    private var hardware = false
    private var encoderPreset = 1
    private var highChroma = false
    private var projection = ExportProjection.PERSPECTIVE
    private var supersample = 1
    private var depthMap = false
    private var applyTimeLanes = true
    private var stereoSeparation = 0.065
    private var orthoScale = 10f
    private var blurSamples = 0
    private var shutterDegrees = 180.0
    private var gifColors = 128
    private var jpegQuality = 92
    private var proresProfile = 3
    private var chapters = true
    private var waitForChunks = true
    private var gameAudio = true
    private var gameAudioVolume = 1.0
    private var fadeIn = 0.0
    private var fadeOut = 0.0
    private val audioFile = ImString("", 512)
    private var audioOffset = 0.0
    private var audioVolume = 1.0
    private val fileName = ImString("", 128)
    private val presetName = ImString("", 64)
    private var fileNameSession: EditorSession? = null
    private val seenDone = HashSet<UUID>()
    private var prefsLoaded = false
    private var badgedJobs = -1

    override fun tick(frame: FrameContext) {
        val backend = context.exports ?: return
        watchJobs(backend)
        val pending =
            backend.queue().handles().count { it.state == ExportState.RUNNING || it.state == ExportState.QUEUED }
        if (pending != badgedJobs) {
            badgedJobs = pending
            dialog.sections = SECTIONS.mapIndexed { index, section ->
                if (index == JOBS && pending > 0) Dialog.Section(
                    section.title,
                    section.icon,
                    pending.toString()
                ) else section
            }
        }
    }

    override fun content(frame: FrameContext) {
        val backend = context.exports
        if (backend == null) {
            Widgets.emptyState("Exporting is unavailable", "The render backend did not start", Icon.WARNING)
            return
        }
        if (!prefsLoaded) {
            prefsLoaded = true
            loadPreset(context.host.preference("export.last") ?: "")
        }
        val session = context.session
        val replay = session?.replay
        if (!backend.ffmpegAvailable && format.needsFfmpeg) format = ExportFormat.PNG_SEQUENCE
        when (dialog.section) {
            PRESETS -> presets()
            JOBS -> jobs(backend)
            else -> {
                if (session == null || replay == null) {
                    Widgets.emptyState(
                        "Open a replay to export",
                        "Set in/out points or pick a clip, then export",
                        Icon.EXPORT
                    )
                    return
                }
                val (start, end) = resolveRange(session, replay.durationNanos)
                val mapping = if (applyTimeLanes) ProjectTimeMapping.build(session.project, start, end) else null
                when (dialog.section) {
                    OUTPUT -> output(backend, start, end, mapping)
                    LOOK -> look()
                    ENCODING -> encodingSection(backend, session, start, end)
                    else -> file(session, start, end, mapping)
                }
            }
        }
    }

    override fun footer(frame: FrameContext) {
        val backend = context.exports ?: return
        val session = context.session
        val replay = session?.replay
        val busy = backend.queue().handles().any { it.state == ExportState.RUNNING }
        val ready = session != null && replay != null && !busy
        if (session == null) {
            ImGui.alignTextToFramePadding()
            Widgets.smallText(if (busy) "An export is running" else "Open a replay to export", EditorTheme.TEXT_DIM.u32)
        }
        ImGui.sameLine()
        val more = EditorFonts.px(30f)
        val screenshot = EditorFonts.px(118f)
        val draft = EditorFonts.px(84f)
        val start = EditorFonts.px(134f)
        Dialog.rightAlign(more, screenshot, draft, start)
        if (Widgets.iconButton(
                "export-more",
                Icon.MORE,
                ImGui.getFrameHeight(),
                "More",
                enabled = ready,
                width = more
            )
        ) ImGui.openPopup("export-more-menu")
        if (ImGui.beginPopup("export-more-menu")) {
            if (session != null && session.project.clips.isNotEmpty() && ImGui.menuItem("Export all clips")) exportAllClips(
                backend,
                session
            )
            if (ImGui.menuItem("Render POV proxy")) renderProxy()
            ImGui.endPopup()
        }
        ImGui.sameLine(0f, EditorFonts.px(8f))
        if (!ready) ImGui.beginDisabled()
        if (Widgets.ghostButton("Screenshot", screenshot) && session != null) screenshot(backend, session)
        Widgets.tooltip("Render the current frame at the export resolution as a PNG (supersampling and projection apply)  F2")
        ImGui.sameLine(0f, EditorFonts.px(8f))
        if (Widgets.ghostButton("Draft", draft) && session != null) start(backend, session, draft = true)
        Widgets.tooltip("Quick low-resolution preview render at half size and 30 fps")
        ImGui.sameLine(0f, EditorFonts.px(8f))
        val io = ImGui.getIO()
        val hotkey = ready && !io.wantTextInput && (ImGui.isKeyPressed(
            ImGuiKey.Enter,
            false
        ) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter, false) || (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.E, false)))
        if ((Widgets.accentButton("Start export", start) || hotkey) && session != null) start(
            backend,
            session,
            draft = false
        )
        Widgets.tooltip("Renders the range with the current camera setup. Esc cancels a running export.  Enter")
        if (!ready) ImGui.endDisabled()
    }

    private fun output(backend: ExportBackend, start: Long, end: Long, mapping: TimeMapping?) {
        if (Widgets.beginProperties("export")) {
            Widgets.property("Format")
            ImGui.setNextItemWidth(-1f)
            Widgets.enumCombo("##format", format) { it.label }?.let {
                format = it
                if (!it.needsFfmpeg || backend.ffmpegAvailable) Unit else format = ExportFormat.PNG_SEQUENCE
                if (it == ExportFormat.GIF && fps > 30) fps = 24
            }
            Widgets.property("Size")
            if (Widgets.popupButton("size", SIZES[sizeIndex].label)) {
                for ((index, size) in SIZES.withIndex()) {
                    if (ImGui.menuItem(size.label, "", index == sizeIndex)) {
                        sizeIndex = index
                        if (size.width > 0) {
                            width = size.width
                            height = size.height
                        }
                    }
                }
                ImGui.endPopup()
            }
            if (SIZES[sizeIndex].width == 0) {
                Widgets.property("")
                val half = (ImGui.getContentRegionAvailX() - EditorFonts.px(6f)) / 2f
                ImGui.setNextItemWidth(half)
                Widgets.intDrag("##customw", width, 8f, 16, 7680, "%d px")?.let { width = it and 1.inv() }
                ImGui.sameLine(0f, EditorFonts.px(6f))
                ImGui.setNextItemWidth(half)
                Widgets.intDrag("##customh", height, 8f, 16, 7680, "%d px")?.let { height = it and 1.inv() }
            }
            Widgets.property("Frame rate")
            Widgets.segmented("fps", FPS.map { it.toString() }, FPS.indexOf(fps), 0f)?.let {
                fps = FPS[it]
                context.timeline.renderFps = fps
            }
            if (fps !in FPS) {
                ImGui.sameLine()
                Widgets.intDrag("##customfps", fps, 0.2f, 1, 240, "%d fps")?.let { fps = it }
            } else {
                ImGui.sameLine()
                if (Widgets.iconButton("fps-custom", Icon.EDIT, ImGui.getFrameHeight(), "Custom frame rate")) fps = 48
            }
            Widgets.property("Range")
            val ranges = Range.entries
            Widgets.segmented("range", ranges.map { it.label }, ranges.indexOf(range), 0f)?.let { range = ranges[it] }
            val outputNanos = mapping?.outputDurationNanos ?: (end - start)
            val frames = (outputNanos / (Nanos.PER_SECOND / fps)).coerceAtLeast(0) + 1
            Widgets.property("")
            Widgets.smallText(
                "${TimeFormat.clock(start)} - ${TimeFormat.clock(end)}    ${TimeFormat.clock(outputNanos)} out    $frames frames",
                EditorTheme.TEXT_DIM.u32
            )
            Widgets.property(
                "Motion blur",
                "Renders several sub-frames per output frame and averages them for film-like motion blur. Cost scales with the sample count."
            )
            Widgets.segmented("blur", BLUR_LABELS, blurSamples, 0f)?.let { blurSamples = it }
            if (blurSamples > 0) {
                Widgets.property("Shutter", "180° is the cinematic default; 360° blurs across the whole frame interval")
                Widgets.doubleSlider("##shutter", shutterDegrees, 45.0, 360.0, "%.0f°")?.let { shutterDegrees = it }
            }
            Widgets.property("Speed & freeze", "Bake speed ramps and freezes into the output timing")
            Widgets.toggle("##timelanes", applyTimeLanes)?.let { applyTimeLanes = it }
            Widgets.endProperties()
        }
    }

    private fun look() {
        if (Widgets.beginProperties("look")) {
            Widgets.property(
                "Projection",
                "360 and cube map renders six views per frame; stereo renders two eyes side by side"
            )
            ImGui.setNextItemWidth(-1f)
            Widgets.enumCombo("##projection", projection) { it.label }?.let {
                projection = it
                if (it == ExportProjection.EQUIRECTANGULAR) {
                    sizeIndex = CUSTOM
                    width = maxOf(width, 4096)
                    height = width / 2
                } else if (it == ExportProjection.CUBE_MAP) {
                    sizeIndex = CUSTOM
                    height = width / 3 * 2
                }
            }
            if (projection == ExportProjection.STEREO_SBS) {
                Widgets.property("Eye separation")
                Widgets.doubleSlider("##ipd", stereoSeparation, 0.02, 0.15, "%.3f m")?.let { stereoSeparation = it }
            }
            if (projection == ExportProjection.ORTHOGRAPHIC) {
                Widgets.property("Ortho scale", "Half-height of the view, in blocks - smaller zooms in")
                Widgets.slider("##orthoscale", orthoScale, 0.5f, 200f, "%.1f")?.let { orthoScale = it }
            }
            Widgets.property(
                "Wait for chunks",
                "Hold each frame until every visible chunk section has compiled, so nothing pops in or goes missing. Costs a few extra game frames when the camera moves fast."
            )
            Widgets.toggle("##waitchunks", waitForChunks)?.let { waitForChunks = it }
            Widgets.property("Supersampling", "Render at 2x or 3x and downscale for smoother edges; slower")
            Widgets.segmented("ssaa", listOf("Off", "2x", "3x", "4x"), supersample - 1, 0f)
                ?.let { supersample = it + 1 }
            if (format == ExportFormat.PNG_SEQUENCE) {
                Widgets.property("Depth map", "Also write a 16-bit grayscale depth image per frame")
                Widgets.toggle("##depth", depthMap)?.let { depthMap = it }
            }
            Widgets.endProperties()
        }
    }

    private fun encodingSection(backend: ExportBackend, session: EditorSession, start: Long, end: Long) {
        when {
            format.needsFfmpeg -> encoding(backend, session, start, end)
            format == ExportFormat.JPEG_SEQUENCE -> if (Widgets.beginProperties("jpeg")) {
                Widgets.property("JPEG quality")
                Widgets.intDrag("##jpegq", jpegQuality, 0.5f, 40, 100, "%d")?.let { jpegQuality = it }
                Widgets.endProperties()
            }

            else -> Widgets.emptyState(
                "Nothing to encode",
                "${format.label} writes lossless frames as they are",
                Icon.IMAGE
            )
        }
    }

    private fun file(session: EditorSession, start: Long, end: Long, mapping: TimeMapping?) {
        if (Widgets.beginProperties("file")) {
            Widgets.property("Name")
            if (fileNameSession !== session) {
                fileNameSession = session
                fileName.set(defaultFileName(session))
            }
            if (fileName.get().isEmpty()) fileName.set(defaultFileName(session))
            ImGui.setNextItemWidth(-1f)
            ImGui.inputText("##filename", fileName, ImGuiInputTextFlags.AutoSelectAll)
            Widgets.property("Folder")
            if (Widgets.iconButton(
                    "open-exports",
                    Icon.FOLDER,
                    ImGui.getFrameHeight(),
                    context.host.exportsDirectory.toString()
                )
            ) openFolder(context.host.exportsDirectory)
            ImGui.sameLine()
            ImGui.alignTextToFramePadding()
            Widgets.smallText(context.host.exportsDirectory.toString(), EditorTheme.TEXT_DIM.u32, clipToWidth = true)
            Widgets.property("Chapters", "Write a YouTube-style chapters text file from the markers inside the range")
            Widgets.toggle("##chapters", chapters)?.let { chapters = it }
            Widgets.property("When done", "Open the export folder after a render finishes")
            Widgets.toggle("##opendone", context.ui.openFolderAfterExport)
                ?.let { context.ui.openFolderAfterExport = it }
            ImGui.sameLine()
            ImGui.alignTextToFramePadding()
            Widgets.mutedText("open folder")
            val estimate = FfmpegCommand.estimateBytes(buildSettings(session, start, end, mapping, draft = false))
            Widgets.property("Estimate")
            Widgets.smallText(
                "~${formatSize(estimate)}    ${TimeFormat.clock(outputNanosFor(mapping, start, end))}",
                EditorTheme.TEXT_DIM.u32
            )
            Widgets.endProperties()
        }
    }

    private fun encoding(backend: ExportBackend, session: EditorSession, start: Long, end: Long) {
        ffmpegStatus(backend)
        if (!backend.ffmpegAvailable) return
        if (Widgets.beginProperties("encoding")) {
            when (format) {
                ExportFormat.GIF -> {
                    Widgets.property("Colours")
                    Widgets.intDrag("##gifcolors", gifColors, 1f, 8, 256, "%d")?.let { gifColors = it }
                }

                ExportFormat.MOV_PRORES -> {
                    Widgets.property("Profile")
                    Widgets.segmented(
                        "prores",
                        PRORES.map { it.first },
                        PRORES.indexOfFirst { it.second == proresProfile },
                        0f
                    )?.let { proresProfile = PRORES[it].second }
                }

                else -> {
                    Widgets.property("Quality")
                    val modes = listOf("Quality (CRF)", "Bitrate", "File size")
                    val modeIndex =
                        if (qualityMode == QualityMode.CRF) 0 else if (targetMegabytes > 0.0 && sizeMode) 2 else 1
                    Widgets.segmented("qmode", modes, modeIndex, 0f)?.let {
                        qualityMode = if (it == 0) QualityMode.CRF else QualityMode.BITRATE
                        sizeMode = it == 2
                    }
                    when {
                        qualityMode == QualityMode.CRF -> {
                            Widgets.property("")
                            Widgets.intDrag("##crf", crf, 0.2f, 0, 40, "CRF %d")?.let { crf = it }
                        }

                        sizeMode -> {
                            Widgets.property("")
                            Widgets.doubleDrag("##size", targetMegabytes, 0.5f, "%.0f MB", 1f, 100000f)
                                ?.let { targetMegabytes = it }
                            ImGui.sameLine()
                            val kbps = FfmpegCommand.bitrateForSize(
                                (targetMegabytes * 1_000_000).toLong(),
                                buildSettings(session, start, end, null, draft = false)
                            )
                            Widgets.smallText(String.format("~%.1f Mbps", kbps / 1000.0), EditorTheme.TEXT_DIM.u32)
                        }

                        else -> {
                            Widgets.property("")
                            Widgets.doubleDrag("##bitrate", bitrateMbps, 0.2f, "%.1f Mbps", 0.5f, 400f)
                                ?.let { bitrateMbps = it }
                        }
                    }
                    val encoders = backend.encoders()
                    val hardwareNames = encoders.filter {
                        it.endsWith("_nvenc") || it.endsWith("_amf") || it.endsWith("_qsv") || it.endsWith("_videotoolbox")
                    }
                    if (hardwareNames.isNotEmpty() && (format == ExportFormat.MP4_H264 || format == ExportFormat.MP4_H265)) {
                        Widgets.property(
                            "Encoder",
                            "Hardware encoders are much faster but slightly lower quality per bit"
                        )
                        Widgets.segmented("hw", listOf("Software", "Hardware"), if (hardware) 1 else 0, 0f)
                            ?.let { hardware = it == 1 }
                        ImGui.sameLine()
                        Widgets.smallText(
                            hardwareNames.first().substringAfter('_').uppercase(),
                            EditorTheme.TEXT_DIM.u32
                        )
                    }
                    Widgets.property("Speed", "Slower presets squeeze more quality into the same size")
                    Widgets.segmented("encpreset", ENCODER_PRESETS, encoderPreset, 0f)?.let { encoderPreset = it }
                    if (format == ExportFormat.MP4_H264 || format == ExportFormat.MP4_H265) {
                        Widgets.property(
                            "Chroma",
                            "4:4:4 keeps full colour detail (sharp text, no colour bleeding) but many players cannot decode it"
                        )
                        Widgets.segmented("chroma", listOf("4:2:0", "4:4:4"), if (highChroma) 1 else 0, 0f)
                            ?.let { highChroma = it == 1 }
                    }
                }
            }
            Widgets.property(
                "Game audio",
                "Renders the sounds the replay plays (hits, blocks, mobs, arrows...) into the clip, positioned from the export camera. Sequences and GIFs get a .wav next to the frames."
            )
            Widgets.toggle("##gameaudio", gameAudio)?.let { gameAudio = it }
            if (gameAudio) {
                Widgets.property("Game volume")
                Widgets.doubleSlider("##gamevolume", gameAudioVolume, 0.0, 2.0, "%.2f")?.let { gameAudioVolume = it }
            }
            Widgets.property("Fade", "Fade from and to black (and the audio track) over this many seconds")
            ImGui.setNextItemWidth(EditorFonts.px(90f))
            Widgets.doubleDrag("##fadein", fadeIn, 0.05f, "in %.1f s", 0f, 30f)?.let { fadeIn = it }
            ImGui.sameLine()
            ImGui.setNextItemWidth(EditorFonts.px(90f))
            Widgets.doubleDrag("##fadeout", fadeOut, 0.05f, "out %.1f s", 0f, 30f)?.let { fadeOut = it }
            if (format.supportsAudio) {
                Widgets.property(
                    "Audio file",
                    "Optional music or voice track muxed into the video (any format ffmpeg reads)"
                )
                ImGui.setNextItemWidth(-1f)
                ImGui.inputTextWithHint("##audio", "Path to an audio file", audioFile)
                if (audioFile.get().isNotBlank() && !Files.isRegularFile(Paths.get(audioFile.get().trim()))) {
                    Widgets.property("")
                    Widgets.pill("file not found - audio will be skipped", EditorTheme.WARNING)
                }
                if (audioFile.get().isNotBlank()) {
                    Widgets.property("Audio offset")
                    Widgets.doubleDrag("##audiooffset", audioOffset, 0.1f, "%.2f s", 0f, 3600f)
                        ?.let { audioOffset = it }
                    Widgets.property("Audio volume")
                    Widgets.doubleSlider("##audiovolume", audioVolume, 0.0, 2.0, "%.2f")?.let { audioVolume = it }
                }
            }
            Widgets.endProperties()
        }
    }

    private var sizeMode = false

    private fun ffmpegStatus(backend: ExportBackend) {
        val downloading = backend.queue().handles()
            .firstOrNull { it.job.name == "download ffmpeg" && (it.state == ExportState.RUNNING || it.state == ExportState.QUEUED) }
        if (backend.ffmpegAvailable) {
            Widgets.smallText("ffmpeg ${backend.ffmpegVersion ?: ""}", EditorTheme.TEXT_DIM.u32, clipToWidth = true)
            Widgets.tooltip(backend.ffmpegExecutable)
            return
        }
        Widgets.pill("ffmpeg not found", EditorTheme.WARNING)
        ImGui.sameLine()
        if (downloading != null) {
            Widgets.progress(downloading.progress.toFloat(), -1f, downloading.detail.ifBlank { "downloading" })
            return
        }
        if (backend.ffmpegDownloadSupported) {
            if (Widgets.accentButton("Download ffmpeg")) {
                backend.downloadFfmpeg()
                context.status("Downloading ffmpeg")
            }
            Widgets.tooltip("Downloads a static ffmpeg build for this system into the recast/ffmpeg folder")
            ImGui.sameLine()
        }
        if (Widgets.ghostButton("Locate")) {
            if (backend.relocateFfmpeg()) context.status("Found ffmpeg ${backend.ffmpegVersion}") else context.status("ffmpeg still not found; install it or set RECAST_FFMPEG")
        }
        Widgets.tooltip("Search PATH and common install folders again")
        Widgets.wrappedText(
            "Videos need ffmpeg. Without it you can still export PNG or JPEG frames.",
            EditorTheme.TEXT_DIM.u32
        )
    }

    private fun presets() {
        Widgets.header("Quick presets")
        for ((index, preset) in quickPresets.withIndex()) {
            val (name, description, apply) = preset
            val pressed = Widgets.row("quick-$index", EditorFonts.px(42f), false) { x, y, _, _ ->
                val list = ImGui.getWindowDrawList()
                list.addText(
                    EditorFonts.bodyMedium,
                    ImGui.getFontSize(),
                    x + EditorFonts.px(10f),
                    y + EditorFonts.px(7f),
                    EditorTheme.TEXT.u32,
                    name
                )
                list.addText(
                    EditorFonts.small,
                    EditorFonts.small.fontSize.toInt(),
                    x + EditorFonts.px(10f),
                    y + EditorFonts.px(24f),
                    EditorTheme.TEXT_MUTED.u32,
                    description
                )
            }
            if (pressed) {
                apply()
                context.status("Applied the $name preset")
            }
        }
        Widgets.header("Saved presets")
        val stored = presetNames()
        ImGui.setNextItemWidth(EditorFonts.px(220f))
        ImGui.inputTextWithHint("##presetname", "Name the current settings", presetName)
        ImGui.sameLine()
        val name = presetName.get().trim()
        if (name.isEmpty()) ImGui.beginDisabled()
        if (Widgets.button("Save")) {
            context.host.setPreference("export.preset." + key(name), encodePreset())
            context.host.setPreference("export.presetNames", (stored + name).distinct().joinToString("|"))
            context.status("Saved export preset $name")
        }
        if (name.isEmpty()) ImGui.endDisabled()
        if (stored.isEmpty()) {
            Widgets.smallText("Presets you save show up here.", EditorTheme.TEXT_DIM.u32)
            return
        }
        ImGui.dummy(0f, EditorFonts.px(2f))
        if (ImGui.beginTable("saved-presets", 3, ImGuiTableFlags.RowBg or ImGuiTableFlags.BordersInnerH)) {
            ImGui.tableSetupColumn("name", ImGuiTableColumnFlags.WidthStretch)
            ImGui.tableSetupColumn("load", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(70f))
            ImGui.tableSetupColumn("delete", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(30f))
            for (saved in stored) {
                ImGui.pushID(saved)
                try {
                    ImGui.tableNextRow()
                    ImGui.tableNextColumn()
                    ImGui.alignTextToFramePadding()
                    ImGui.textUnformatted(saved)
                    ImGui.tableNextColumn()
                    if (Widgets.ghostButton("Load", EditorFonts.px(64f))) {
                        context.host.preference("export.preset." + key(saved))?.let { loadPreset(it) }
                        presetName.set(saved)
                        context.status("Loaded export preset $saved")
                    }
                    ImGui.tableNextColumn()
                    if (Widgets.iconButton(
                            "delete",
                            Icon.TRASH,
                            ImGui.getFrameHeight(),
                            "Delete preset",
                            color = EditorTheme.TEXT_MUTED.u32
                        )
                    ) {
                        context.host.setPreference("export.presetNames", (stored - saved).joinToString("|"))
                        context.host.setPreference("export.preset." + key(saved), "")
                    }
                } finally {
                    ImGui.popID()
                }
            }
            ImGui.endTable()
        }
    }

    private fun key(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-")

    private fun presetNames(): List<String> =
        context.host.preference("export.presetNames")?.split('|')?.filter { it.isNotBlank() } ?: emptyList()

    private fun encodePreset(): String = listOf(
        "format=${format.name}",
        "size=$sizeIndex",
        "width=$width",
        "height=$height",
        "fps=$fps",
        "quality=${qualityMode.name}",
        "crf=$crf",
        "bitrate=$bitrateMbps",
        "mb=$targetMegabytes",
        "sizeMode=$sizeMode",
        "hw=$hardware",
        "preset=$encoderPreset",
        "chroma=$highChroma",
        "projection=${projection.name}",
        "orthoScale=$orthoScale",
        "ssaa=$supersample",
        "blur=$blurSamples",
        "shutter=$shutterDegrees",
        "gif=$gifColors",
        "jpeg=$jpegQuality",
        "prores=$proresProfile",
        "chapters=$chapters",
        "lanes=$applyTimeLanes",
        "fadeIn=$fadeIn",
        "fadeOut=$fadeOut",
        "waitChunks=$waitForChunks",
        "gameAudio=$gameAudio",
        "gameVolume=$gameAudioVolume",
    ).joinToString(";")

    private fun loadPreset(encoded: String) {
        if (encoded.isBlank()) return
        for (entry in encoded.split(';')) {
            val (k, v) = entry.split('=', limit = 2).let { if (it.size == 2) it[0] to it[1] else continue }
            runCatching {
                when (k) {
                    "format" -> format = ExportFormat.valueOf(v)
                    "size" -> sizeIndex = v.toInt().coerceIn(0, SIZES.size - 1)
                    "width" -> width = v.toInt()
                    "height" -> height = v.toInt()
                    "fps" -> fps = v.toInt()
                    "quality" -> qualityMode = QualityMode.valueOf(v)
                    "crf" -> crf = v.toInt()
                    "bitrate" -> bitrateMbps = v.toDouble()
                    "mb" -> targetMegabytes = v.toDouble()
                    "sizeMode" -> sizeMode = v.toBoolean()
                    "hw" -> hardware = v.toBoolean()
                    "preset" -> encoderPreset = v.toInt().coerceIn(0, 2)
                    "chroma" -> highChroma = v.toBoolean()
                    "projection" -> projection = ExportProjection.valueOf(v)
                    "orthoScale" -> orthoScale = v.toFloat()
                    "ssaa" -> supersample = v.toInt().coerceIn(1, 4)
                    "blur" -> blurSamples = v.toInt().coerceIn(0, BLUR_LABELS.size - 1)
                    "shutter" -> shutterDegrees = v.toDouble()
                    "gif" -> gifColors = v.toInt()
                    "jpeg" -> jpegQuality = v.toInt()
                    "prores" -> proresProfile = v.toInt()
                    "chapters" -> chapters = v.toBoolean()
                    "lanes" -> applyTimeLanes = v.toBoolean()
                    "fadeIn" -> fadeIn = v.toDouble()
                    "fadeOut" -> fadeOut = v.toDouble()
                    "waitChunks" -> waitForChunks = v.toBoolean()
                    "gameAudio" -> gameAudio = v.toBoolean()
                    "gameVolume" -> gameAudioVolume = v.toDouble()
                }
            }
        }
    }

    private fun outputNanosFor(mapping: TimeMapping?, start: Long, end: Long): Long =
        mapping?.outputDurationNanos ?: (end - start)

    private fun buildSettings(
        session: EditorSession,
        start: Long,
        end: Long,
        mapping: TimeMapping?,
        draft: Boolean
    ): ExportSettings {
        val name =
            ClipActions.safeName(fileName.get().ifBlank { defaultFileName(session) }) + if (draft) "-draft" else ""
        val outWidth = if (draft) (width / 2) and 1.inv() else width
        val outHeight = if (draft) (height / 2) and 1.inv() else height
        val outFps = if (draft) minOf(fps, 30) else fps
        val base = ExportSettings(
            width = outWidth,
            height = outHeight,
            fps = outFps,
            startNanos = start,
            endNanos = end,
            output = context.host.exportsDirectory.resolve("$name.${format.extension}"),
            crf = if (draft) 26 else crf,
            preset = if (draft) "fast" else ENCODER_PRESETS[encoderPreset].lowercase(),
            pixelFormat = if (highChroma && !draft) "yuv444p" else "yuv420p",
            supersample = if (draft) 1 else supersample,
            depthMap = depthMap && format == ExportFormat.PNG_SEQUENCE && !draft,
            projection = projection,
            stereoSeparation = stereoSeparation,
            orthoScale = orthoScale,
            timeMapping = mapping,
            audioFile = audioFile.get().trim().takeIf { it.isNotBlank() && format.supportsAudio }?.let { Paths.get(it) }
                ?.takeIf { Files.isRegularFile(it) },
            audioOffsetSeconds = audioOffset,
            audioVolume = audioVolume,
            format = format,
            qualityMode = if (draft) QualityMode.CRF else qualityMode,
            hardware = hardware,
            motionBlur = if (draft || blurSamples == 0) MotionBlur.OFF else MotionBlur(
                BLUR_SAMPLES[blurSamples],
                shutterDegrees / 360.0
            ),
            gifColors = gifColors,
            jpegQuality = jpegQuality,
            proresProfile = proresProfile,
            waitForChunks = waitForChunks,
            gameAudio = gameAudio,
            gameAudioVolume = gameAudioVolume,
            fadeInSeconds = if (draft) 0.0 else fadeIn,
            fadeOutSeconds = if (draft) 0.0 else fadeOut,
        )
        if (base.qualityMode != QualityMode.BITRATE) return base
        val kbps = if (sizeMode) FfmpegCommand.bitrateForSize(
            (targetMegabytes * 1_000_000).toLong(),
            base
        ) else (bitrateMbps * 1000).toInt()
        return base.copy(bitrateKbps = kbps)
    }

    private var proxyRestore: (() -> Unit)? = null
    private var proxyHandle: UUID? = null

    fun renderProxy() {
        val backend = context.exports ?: return
        val session = context.session ?: return
        val replay = session.replay ?: return
        if (backend.queue().handles().any { it.state == ExportState.RUNNING }) {
            context.status("An export is already running")
            return
        }
        val control = context.host.camera
        val settings = control.settings
        val previousMode = settings.mode
        val previousTarget = settings.targetEntityId
        val previousExact = settings.exactFirstPerson
        val lane = session.project.lane(LaneKind.CAMERA)
        val previousMuted = lane.muted
        settings.mode = CameraMode.FIRST_PERSON
        settings.targetEntityId = CameraSettings.TARGET_RECORDER
        settings.exactFirstPerson = true
        control.apply()
        if (!previousMuted) session.execute(SetLaneState(LaneKind.CAMERA, lane.copy(muted = true)))
        val name = session.project.recording.fileName.toString().substringBeforeLast('.') + "-pov"
        val exportSettings = ExportSettings(
            width = 1280,
            height = 720,
            fps = 30,
            startNanos = 0L,
            endNanos = replay.durationNanos,
            output = context.host.exportsDirectory.resolve("$name.mp4"),
            crf = 26,
            preset = "fast",
            format = if (backend.ffmpegAvailable) ExportFormat.MP4_H264 else ExportFormat.JPEG_SEQUENCE,
            hardware = true,
        )
        val handle = backend.submit(
            ExportRequest(
                name,
                exportSettings.copy(output = context.host.exportsDirectory.resolve("$name.${exportSettings.format.extension}")),
                if (exportSettings.format.needsFfmpeg) ExportTarget.VIDEO else ExportTarget.PNG_SEQUENCE
            )
        )
        if (handle == null) {
            context.status("Could not queue the POV proxy")
            return
        }
        proxyHandle = handle.id
        proxyRestore = {
            settings.mode = previousMode
            settings.targetEntityId = previousTarget
            settings.exactFirstPerson = previousExact
            control.apply()
            val current = session.project.lane(LaneKind.CAMERA)
            if (current.muted != previousMuted) session.execute(
                SetLaneState(
                    LaneKind.CAMERA,
                    current.copy(muted = previousMuted)
                )
            )
        }
        context.status("Rendering POV proxy: the whole recording from the recorder's eyes at 720p")
    }

    fun screenshotNow() {
        val backend = context.exports ?: return
        val session = context.session ?: return
        screenshot(backend, session)
    }

    fun startNow() {
        val backend = context.exports ?: return
        val session = context.session ?: return
        if (backend.queue().handles().any { it.state == ExportState.RUNNING }) {
            context.status("An export is already running")
            return
        }
        start(backend, session, draft = false)
    }

    private fun start(backend: ExportBackend, session: EditorSession, draft: Boolean) {
        val replay = session.replay ?: return
        val (start, end) = resolveRange(session, replay.durationNanos)
        if (end - start < Nanos.PER_TICK) {
            context.status("Nothing to export: the range is empty")
            return
        }
        val mapping = if (applyTimeLanes) ProjectTimeMapping.build(session.project, start, end) else null
        val settings = buildSettings(session, start, end, mapping, draft)
        val target = if (format.needsFfmpeg) ExportTarget.VIDEO else ExportTarget.PNG_SEQUENCE
        val handle = backend.submit(
            ExportRequest(
                settings.output.fileName.toString().substringBeforeLast('.'),
                settings,
                target
            )
        )
        if (chapters && !draft) writeChapters(session, settings, start, end)
        context.host.setPreference("export.last", encodePreset())
        context.status(if (handle != null) "Export queued: ${settings.output.fileName}" else "Could not queue the export")
    }

    private fun exportAllClips(backend: ExportBackend, session: EditorSession) {
        var queued = 0
        for ((_, _, _, startNanos, endNanos, title1) in session.project.clips.sortedBy { it.startNanos }) {
            val mapping =
                if (applyTimeLanes) ProjectTimeMapping.build(session.project, startNanos, endNanos) else null
            val base = buildSettings(session, startNanos, endNanos, mapping, draft = false)
            val settings =
                base.copy(output = context.host.exportsDirectory.resolve("${ClipActions.safeName(title1)}.${format.extension}"))
            backend.submit(
                ExportRequest(
                    ClipActions.safeName(title1),
                    settings,
                    if (format.needsFfmpeg) ExportTarget.VIDEO else ExportTarget.PNG_SEQUENCE
                )
            )
            queued++
        }
        context.status("Queued $queued clip exports")
    }

    private fun writeChapters(session: EditorSession, settings: ExportSettings, start: Long, end: Long) {
        val markers = session.project.markers.filter { it.nanos in start..end }.sortedBy { it.nanos }
        if (markers.isEmpty()) return
        val lines = ArrayList<String>()
        if (markers.first().nanos > start) lines += "00:00 Start"
        for ((_, nanos, label) in markers) {
            val seconds = (nanos - start) / Nanos.PER_SECOND
            lines += String.format("%02d:%02d %s", seconds / 60, seconds % 60, label)
        }
        val path = settings.output.resolveSibling(
            settings.output.fileName.toString().substringBeforeLast('.') + ".chapters.txt"
        )
        runCatching {
            Files.createDirectories(path.parent)
            Files.write(path, lines)
        }.onFailure { context.status("Could not write chapters: ${it.message}") }
    }

    private fun screenshot(backend: ExportBackend, session: EditorSession) {
        val replay = session.replay ?: return
        val at = replay.positionNanos
        val name = "screenshot-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val settings = ExportSettings(
            width = width,
            height = height,
            fps = fps,
            startNanos = at,
            endNanos = at,
            output = context.host.exportsDirectory.resolve("$name.png"),
            supersample = supersample,
            depthMap = depthMap,
            projection = projection,
            stereoSeparation = stereoSeparation,
            orthoScale = orthoScale,
            format = ExportFormat.PNG_SEQUENCE,
        )
        backend.submit(ExportRequest(name, settings, ExportTarget.PNG_SEQUENCE))
        context.status("Screenshot queued: $name")
    }

    private fun watchJobs(backend: ExportBackend) {
        val proxy = proxyHandle
        if (proxy != null) {
            val handle = backend.queue().handles().firstOrNull { it.id == proxy }
            if (handle == null || (handle.state != ExportState.RUNNING && handle.state != ExportState.QUEUED)) {
                proxyHandle = null
                proxyRestore?.invoke()
                proxyRestore = null
            }
        }
        for (handle in backend.queue().handles()) {
            if (handle.state != ExportState.DONE || handle.id in seenDone) continue
            seenDone += handle.id
            val result = handle.result ?: continue
            if (handle.job.name.startsWith("export ")) {
                val size = runCatching {
                    if (Files.isDirectory(result)) Files.list(result)
                        .use { s -> s.mapToLong { Files.size(it) }.sum() } else Files.size(result)
                }.getOrDefault(0L)
                context.status("Export finished: ${result.fileName}    ${formatSize(size)}")
                if (context.ui.openFolderAfterExport) openFolder(result)
            }
        }
    }

    private fun openFolder(path: Path) {
        runCatching {
            val target = if (Files.isDirectory(path)) path else path.parent
            java.awt.Desktop.getDesktop().open(target.toFile())
        }.onFailure { context.status("Could not open folder: ${it.message}") }
    }

    private fun singleImage(path: Path): Path? {
        if (!Files.isDirectory(path)) return path.takeIf { it.fileName.toString().endsWith(".png") }
        val files = runCatching {
            Files.list(path).use { stream ->
                stream.filter {
                    it.fileName.toString().endsWith(".png") && !it.fileName.toString().contains("-depth")
                }.toList()
            }
        }.getOrDefault(emptyList())
        return files.singleOrNull()
    }

    private fun copyImage(path: Path) {
        runCatching {
            val image = javax.imageio.ImageIO.read(path.toFile())
            val transferable = object : java.awt.datatransfer.Transferable {
                override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)

                override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) =
                    flavor == java.awt.datatransfer.DataFlavor.imageFlavor

                override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any = image
            }
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
        }.onSuccess { context.status("Image copied to the clipboard") }
            .onFailure { context.status("Could not copy image: ${it.message}") }
    }

    private fun play(path: Path) {
        runCatching {
            java.awt.Desktop.getDesktop().open(path.toFile())
        }.onFailure { context.status("Could not open: ${it.message}") }
    }

    private fun jobs(backend: ExportBackend) {
        val handles = backend.queue().handles()
        if (handles.isEmpty()) {
            Widgets.emptyState("No exports yet", "Finished and running renders show up here", Icon.LIST)
            return
        }
        if (handles.any { it.state == ExportState.DONE || it.state == ExportState.FAILED || it.state == ExportState.CANCELLED }) {
            Dialog.rightAlign(EditorFonts.px(92f))
            if (Widgets.ghostButton(
                    "Clear done",
                    EditorFonts.px(92f)
                )
            ) handles.filter { it.state != ExportState.RUNNING && it.state != ExportState.QUEUED }
                .forEach { backend.queue().forget(it.id) }
        }
        for (handle in handles.sortedByDescending { it.startedAtNanos }) {
            ImGui.pushID(handle.id.toString())
            try {
                job(backend, handle)
            } finally {
                ImGui.popID()
            }
        }
    }

    private fun job(backend: ExportBackend, handle: ExportHandle) {
        val state = handle.state
        val color = when (state) {
            ExportState.RUNNING -> EditorTheme.ACCENT
            ExportState.DONE -> EditorTheme.SUCCESS
            ExportState.FAILED -> EditorTheme.RECORD
            ExportState.CANCELLED, ExportState.QUEUED -> EditorTheme.CONTROL_ACTIVE
        }
        Widgets.pill(state.name.lowercase(), color)
        ImGui.sameLine()
        ImGui.alignTextToFramePadding()
        ImGui.textUnformatted(handle.job.name)
        if (state == ExportState.DONE && handle.finishedAtNanos > handle.startedAtNanos) {
            ImGui.sameLine()
            Widgets.smallText(
                TimeFormat.clock(handle.finishedAtNanos - handle.startedAtNanos),
                EditorTheme.TEXT_DIM.u32
            )
        }
        when (state) {
            ExportState.RUNNING, ExportState.QUEUED -> {
                Widgets.progress(
                    handle.progress.toFloat(),
                    -EditorFonts.px(70f),
                    String.format("%.0f%%", handle.progress * 100)
                )
                ImGui.sameLine()
                if (Widgets.dangerButton("Cancel")) handle.cancel()
                if (handle.detail.isNotBlank()) Widgets.smallText(handle.detail, EditorTheme.TEXT_DIM.u32)
            }

            ExportState.DONE -> handle.result?.let { path ->
                Widgets.smallText(path.fileName.toString(), EditorTheme.TEXT_DIM.u32)
                ImGui.sameLine()
                singleImage(path)?.let { image ->
                    if (Widgets.smallButton("Copy image")) copyImage(image)
                    ImGui.sameLine()
                }
                if (!Files.isDirectory(path) && Widgets.smallButton("Play")) play(path)
                if (!Files.isDirectory(path)) ImGui.sameLine()
                if (Widgets.smallButton("Open folder")) openFolder(path)
                ImGui.sameLine()
                if (Widgets.smallButton("Copy path")) ImGui.setClipboardText(path.toAbsolutePath().toString())
                ImGui.sameLine()
                if (Widgets.smallButton("Clear")) backend.queue().forget(handle.id)
            }

            ExportState.FAILED -> {
                Widgets.wrappedText(handle.failure?.message ?: "Unknown error", EditorTheme.RECORD.u32)
                if (Widgets.smallButton("Clear")) backend.queue().forget(handle.id)
            }

            ExportState.CANCELLED -> {
                ImGui.sameLine()
                if (Widgets.smallButton("Clear")) backend.queue().forget(handle.id)
            }
        }
    }

    private fun resolveRange(session: EditorSession, duration: Long): Pair<Long, Long> = when (range) {
        Range.WHOLE -> 0L to duration
        Range.CLIP -> session.selection.clipIds.firstOrNull()?.let { session.project.clip(it) }
            ?.let { it.startNanos to it.endNanos }
            ?: (session.project.inPointNanos to (if (session.project.outPointNanos > 0L) session.project.outPointNanos else duration))

        Range.IN_OUT -> session.project.inPointNanos to (if (session.project.outPointNanos > 0L) session.project.outPointNanos else duration)
    }

    private fun defaultFileName(session: EditorSession): String {
        val base = session.project.recording.fileName.toString().substringBeforeLast('.')
        return when (range) {
            Range.CLIP -> session.selection.clipIds.firstOrNull()?.let { session.project.clip(it) }
                ?.let { ClipActions.safeName(it.title) } ?: base

            else -> base
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format("%.0f MB", bytes / 1048576.0)
        else -> String.format("%.0f kB", bytes / 1024.0)
    }

    private val quickPresets: List<Triple<String, String, () -> Unit>> = listOf(
        Triple("YouTube", "1080p at 60 fps, H.264 CRF 16, slow encoder preset") {
            format = ExportFormat.MP4_H264
            sizeIndex = 1
            width = 1920
            height = 1080
            fps = 60
            qualityMode = QualityMode.CRF
            crf = 16
            highChroma = false
            encoderPreset = 2
        },
        Triple("Discord", "1080p at 60 fps, sized to fit a 25 MB upload") {
            format = ExportFormat.MP4_H264
            sizeIndex = 1
            width = 1920
            height = 1080
            fps = 60
            qualityMode = QualityMode.BITRATE
            sizeMode = true
            targetMegabytes = 24.0
            encoderPreset = 1
        },
        Triple("Shorts", "Vertical 1080 x 1920 at 60 fps, CRF 18") {
            format = ExportFormat.MP4_H264
            sizeIndex = SIZES.indexOfFirst { it.label.startsWith("Vertical 1080") }
            width = 1080
            height = 1920
            fps = 60
            qualityMode = QualityMode.CRF
            crf = 18
        },
        Triple("Edit master", "ProRes HQ 1080p with 2x supersampling for further editing") {
            format = ExportFormat.MOV_PRORES
            proresProfile = 3
            sizeIndex = 1
            width = 1920
            height = 1080
            supersample = 2
        },
        Triple("GIF", "720p at 24 fps with a 128 colour palette") {
            format = ExportFormat.GIF
            sizeIndex = SIZES.indexOfFirst { it.label.startsWith("720p") }
            width = 1280
            height = 720
            fps = 24
            gifColors = 128
        },
    )

    private companion object {
        const val OUTPUT = 0
        const val LOOK = 1
        const val ENCODING = 2
        const val PRESETS = 4
        const val JOBS = 5
        val SECTIONS = listOf(
            Dialog.Section("Output", Icon.MONITOR),
            Dialog.Section("Look", Icon.APERTURE),
            Dialog.Section("Encoding", Icon.COMPRESS),
            Dialog.Section("File", Icon.FOLDER),
            Dialog.Section("Presets", Icon.BOOKMARK),
            Dialog.Section("Jobs", Icon.LIST),
        )
        val SIZES = listOf(
            Size("720p    1280 x 720", 1280, 720),
            Size("1080p    1920 x 1080", 1920, 1080),
            Size("1440p    2560 x 1440", 2560, 1440),
            Size("4K    3840 x 2160", 3840, 2160),
            Size("8K    7680 x 4320", 7680, 4320),
            Size("Vertical 1080    1080 x 1920", 1080, 1920),
            Size("Vertical 4K    2160 x 3840", 2160, 3840),
            Size("Square    1080 x 1080", 1080, 1080),
            Size("Ultrawide    2560 x 1080", 2560, 1080),
            Size("Ultrawide 1440    3440 x 1440", 3440, 1440),
            Size("Cinemascope    1920 x 804", 1920, 804),
            Size("Custom", 0, 0),
        )
        val CUSTOM = SIZES.size - 1
        val FPS = listOf(24, 30, 60, 120)
        val ENCODER_PRESETS = listOf("Fast", "Medium", "Slow")
        val BLUR_LABELS = listOf("Off", "2", "4", "8", "16")
        val BLUR_SAMPLES = intArrayOf(1, 2, 4, 8, 16)
        val PRORES = listOf("Proxy" to 0, "LT" to 1, "Standard" to 2, "HQ" to 3, "4444" to 4)
    }
}
