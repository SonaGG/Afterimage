package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.camera.CameraMode
import gg.sona.afterimage.camera.CameraSettings
import gg.sona.afterimage.clip.export.ExportHandle
import gg.sona.afterimage.clip.export.ExportState
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.EditorSession
import gg.sona.afterimage.editor.LaneKind
import gg.sona.afterimage.editor.commands.SetLaneState
import gg.sona.afterimage.render.*
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

class ExportPanel(private val context: EditorContext) :
    DialogPanel("Export", Icon.EXPORT, 920f, 640f, SECTIONS, hasFooter = true) {

    private enum class Range(val label: String) { IN_OUT("In to out"), WHOLE("Whole replay"), CLIP("Selected clip") }

    private class Size(val name: String, val width: Int, val height: Int) {
        val custom: Boolean get() = width == 0
        val dimensions: String get() = "$width × $height"
    }

    private class QuickPreset(val name: String, val description: String, val apply: () -> Unit)

    private var format = ExportFormat.MP4_H264
    private var sizeIndex = 1
    private var width = 1920
    private var height = 1080
    private var fps = 60
    private var range = Range.IN_OUT
    private var qualityMode = QualityMode.CRF
    private var sizeMode = false
    private var crf = 18
    private var bitrateMbps = 20.0
    private var targetMegabytes = 25.0
    private var hardware = false
    private var encoderPreset = 1
    private var highChroma = false
    private var projection = ExportProjection.PERSPECTIVE
    private var supersampleIndex = 0
    private var depthMap = false
    private var depthRange = 0.0
    private var alpha = false
    private var applyLook = true
    private var applyTimeLanes = true
    private var stereoSeparation = 0.065
    private var orthoScale = 10f
    private var blurIndex = 0
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
    private var audioOffset = 0.0
    private var audioVolume = 1.0
    private val audioFile = ImString("", 512)
    private val fileName = ImString("", 128)
    private val presetName = ImString("", 64)
    private var fileNameSession: EditorSession? = null
    private var appliedPreset: Pair<String, String>? = null
    private var focusPresetName = false
    private val seenDone = HashSet<UUID>()
    private val sizes = HashMap<UUID, Long>()
    private val stats = ExportStats()
    private var prefsLoaded = false
    private var badgedJobs = -1

    override fun tick(frame: FrameContext) {
        val backend = context.exports ?: return
        watchJobs(backend)
        val pending = backend.queue().handles().count { it.state == ExportState.RUNNING || it.state == ExportState.QUEUED }
        if (pending != badgedJobs) {
            badgedJobs = pending
            dialog.sections = SECTIONS.mapIndexed { index, section ->
                if (index == QUEUE && pending > 0) Dialog.Section(section.title, section.icon, pending.toString()) else section
            }
        }
        if (!open.get()) return
        val session = context.session
        val replay = session?.replay
        dialog.chips = if (session != null && replay != null && dialog.section != QUEUE && dialog.section != PRESETS) {
            val (start, end) = resolveRange(session, replay.durationNanos)
            val settings = buildSettings(session, start, end, mapping(session, start, end), draft = false)
            ExportLabels.output(settings).take(2) + ExportLabels.length(settings)
        } else emptyList()
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
        when (dialog.section) {
            PRESETS -> presets()
            QUEUE -> queue(backend)
            else -> {
                val session = context.session
                val replay = session?.replay
                if (session == null || replay == null) {
                    Widgets.emptyState("Open a replay to export", "Set in and out points or pick a clip, then export", Icon.EXPORT)
                    return
                }
                val (start, end) = resolveRange(session, replay.durationNanos)
                when (dialog.section) {
                    VIDEO -> video(backend, session, start, end)
                    AUDIO -> audio()
                    else -> advanced(session)
                }
            }
        }
    }

    override fun footer(frame: FrameContext) {
        val backend = context.exports ?: return
        val session = context.session
        val replay = session?.replay
        val busy = backend.queue().handles().any { it.state == ExportState.RUNNING && it.job.name != "download ffmpeg" }
        val ready = session != null && replay != null && !busy && (backend.ffmpegAvailable || !format.needsFfmpeg)
        val blocker = when {
            session == null -> "Open a replay to export"
            busy -> "An export is already running"
            format.needsFfmpeg && !backend.ffmpegAvailable -> "Download ffmpeg to export video"
            else -> null
        }
        val spacing = EditorFonts.px(8f)
        val frameHeight = ImGui.getFrameHeight()
        val more = frameHeight + EditorFonts.px(6f)
        val screenshot = Widgets.buttonWidth("Screenshot", Widgets.ButtonStyle.GHOST)
        val draft = Widgets.buttonWidth("Draft", Widgets.ButtonStyle.GHOST)
        val export = maxOf(EditorFonts.px(96f), Widgets.buttonWidth("Export", Widgets.ButtonStyle.ACCENT))
        val buttons = more + screenshot + draft + export + spacing * 3f
        val blockerWidth = if (blocker == null) 0f else EditorFonts.with(EditorFonts.smallMedium) { Widgets.textWidth(blocker) } + EditorFonts.px(22f)
        if (session != null) {
            if (fileNameSession !== session) {
                fileNameSession = session
                fileName.set(defaultFileName(session))
            }
            if (fileName.get().isEmpty()) fileName.set(defaultFileName(session))
            if (Widgets.iconButton("export-folder", Icon.FOLDER, frameHeight, context.host.exportsDirectory.toString(), color = EditorTheme.TEXT_MUTED.u32)) {
                openFolder(context.host.exportsDirectory)
            }
            ImGui.sameLine(0f, spacing)
            val extension = "." + format.extension
            val extensionWidth = Widgets.textWidth(extension)
            val nameWidth = (ImGui.getContentRegionAvailX() - buttons - extensionWidth - blockerWidth - spacing * 4f).coerceIn(EditorFonts.px(120f), EditorFonts.px(320f))
            ImGui.setNextItemWidth(nameWidth)
            ImGui.inputTextWithHint("##filename", "File name", fileName, ImGuiInputTextFlags.AutoSelectAll)
            Widgets.tooltip("Saved to ${context.host.exportsDirectory}")
            ImGui.sameLine(0f, EditorFonts.px(4f))
            ImGui.alignTextToFramePadding()
            Widgets.mutedText(extension)
            ImGui.sameLine()
        }
        if (blocker != null) {
            if (session != null) ImGui.sameLine(0f, spacing)
            Widgets.pill(blocker, EditorTheme.WARNING)
            ImGui.sameLine()
        }
        Widgets.rightAlign(more, screenshot, draft, export, spacing = spacing)
        if (Widgets.iconButton("export-more", Icon.MORE, frameHeight, "More", enabled = ready, width = more)) ImGui.openPopup("export-more-menu")
        if (ImGui.beginPopup("export-more-menu")) {
            if (session != null && session.project.clips.isNotEmpty() && Menus.item("Export every clip")) exportAllClips(backend, session)
            if (Menus.item("Render POV proxy")) renderProxy()
            ImGui.endPopup()
        }
        ImGui.sameLine(0f, spacing)
        if (!ready) ImGui.beginDisabled()
        if (Widgets.ghostButton("Screenshot") && session != null) screenshot(backend, session)
        Widgets.tooltip("Render the current frame at the export resolution as a PNG  F2")
        ImGui.sameLine(0f, spacing)
        if (Widgets.ghostButton("Draft") && session != null) start(backend, session, draft = true)
        Widgets.tooltip("Quick preview at half size and 30 fps")
        ImGui.sameLine(0f, spacing)
        val io = ImGui.getIO()
        val hotkey = ready && !io.wantTextInput && (ImGui.isKeyPressed(ImGuiKey.Enter, false) ||
                ImGui.isKeyPressed(ImGuiKey.KeypadEnter, false) || (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.E, false)))
        if ((Widgets.accentButton("Export", export) || hotkey) && session != null) start(backend, session, draft = false)
        if (!ready) ImGui.endDisabled()
        Widgets.tooltip("Render the range with the current camera setup. Esc cancels a running export.  Enter")
    }

    private fun video(backend: ExportBackend, session: EditorSession, start: Long, end: Long) {
        if (Widgets.beginProperties("export-video")) {
            Widgets.property("Preset")
            if (Widgets.popupButton("preset", presetLabel())) {
                for (preset in quickPresets) {
                    if (Menus.item(preset.name)) applyQuickPreset(preset)
                    Widgets.tooltip(preset.description)
                }
                val stored = presetNames()
                if (stored.isNotEmpty()) ImGui.separator()
                for (saved in stored) if (Menus.item(saved)) loadSavedPreset(saved)
                ImGui.separator()
                if (Menus.item("Save current as…")) {
                    dialog.section = PRESETS
                    focusPresetName = true
                }
                Widgets.endPopup()
            }
            Widgets.property("Format", format.description)
            Widgets.enumCombo("##format", format) { it.label }?.let { chooseFormat(it) }
            Widgets.property("Resolution")
            val size = SIZES[sizeIndex]
            val sizeButton = EditorFonts.px(150f)
            if (Widgets.popupButton("size", size.name, sizeButton)) {
                for ((index, option) in SIZES.withIndex()) {
                    if (Menus.item(option.name, if (option.custom) "" else option.dimensions, index == sizeIndex)) {
                        sizeIndex = index
                        if (!option.custom) {
                            width = option.width
                            height = option.height
                        }
                    }
                }
                Widgets.endPopup()
            }
            ImGui.sameLine(0f, EditorFonts.px(8f))
            if (size.custom) {
                val field = EditorFonts.px(92f)
                ImGui.setNextItemWidth(field)
                Widgets.intDrag("##customw", width, 8f, 16, 7680, "%d px")?.let { width = it and 1.inv() }
                ImGui.sameLine(0f, EditorFonts.px(6f))
                ImGui.setNextItemWidth(field)
                Widgets.intDrag("##customh", height, 8f, 16, 7680, "%d px")?.let { height = it and 1.inv() }
            } else {
                Widgets.chip(size.dimensions)
            }
            Widgets.property("Frame rate")
            val customFps = fps !in FPS
            val trailing = if (customFps) EditorFonts.px(90f) else ImGui.getFrameHeight()
            Widgets.segmented("fps", FPS.map { it.toString() }, FPS.indexOf(fps), 0f, reserve = trailing + ImGui.getStyle().itemSpacingX)?.let { chooseFps(FPS[it]) }
            ImGui.sameLine()
            if (customFps) {
                ImGui.setNextItemWidth(trailing)
                Widgets.intDrag("##customfps", fps, 0.2f, 1, 240, "%d fps")?.let { chooseFps(it) }
            } else if (Widgets.iconButton("fps-custom", Icon.EDIT, trailing, "Custom frame rate", color = EditorTheme.TEXT_MUTED.u32)) chooseFps(48)
            Widgets.property("Range")
            val ranges = Range.entries
            Widgets.segmented("range", ranges.map { it.label }, ranges.indexOf(range), 0f)?.let { range = ranges[it] }
            Widgets.property("Duration")
            val mapping = mapping(session, start, end)
            val outputNanos = mapping?.outputDurationNanos ?: (end - start)
            val frames = outputNanos / (Nanos.PER_SECOND / fps) + 1
            Widgets.rangeText(TimeFormat.clock(start), TimeFormat.clock(end), EditorTheme.TEXT_MUTED.u32)
            ImGui.sameLine(0f, EditorFonts.px(12f))
            Widgets.chips(listOf(TimeFormat.short(outputNanos), "$frames frames"))
            encoding(backend, session, start, end)
            Widgets.endProperties()
        }
        if (format.needsFfmpeg) ffmpegStatus(backend)
    }

    private fun encoding(backend: ExportBackend, session: EditorSession, start: Long, end: Long) {
        when (format) {
            ExportFormat.GIF -> {
                Widgets.property("Colours", "Palette size; fewer colours make smaller files")
                Widgets.intDrag("##gifcolors", gifColors, 1f, 8, 256, "%d")?.let { gifColors = it }
            }

            ExportFormat.JPEG_SEQUENCE -> {
                Widgets.property("JPEG quality")
                Widgets.intDrag("##jpegq", jpegQuality, 0.5f, 40, 100, "%d")?.let { jpegQuality = it }
            }

            ExportFormat.PNG_SEQUENCE -> Unit

            ExportFormat.MOV_PRORES -> {
                Widgets.property("Profile", "HQ is the editing standard; 4444 keeps full chroma")
                Widgets.segmented("prores", PRORES.map { it.first }, PRORES.indexOfFirst { it.second == proresProfile }, 0f)
                    ?.let { proresProfile = PRORES[it].second }
            }

            else -> {
                Widgets.property("Quality", "CRF: constant quality, size varies. Bitrate: constant size per second. File size: hit a target size.")
                val valueWidth = EditorFonts.px(112f)
                val modeIndex = if (qualityMode == QualityMode.CRF) 0 else if (sizeMode) 2 else 1
                Widgets.segmented("qmode", QUALITY_MODES, modeIndex, 0f, reserve = valueWidth + EditorFonts.px(8f))?.let {
                    qualityMode = if (it == 0) QualityMode.CRF else QualityMode.BITRATE
                    sizeMode = it == 2
                }
                ImGui.sameLine(0f, EditorFonts.px(8f))
                ImGui.setNextItemWidth(valueWidth)
                when (modeIndex) {
                    0 -> Widgets.intDrag("##crf", crf, 0.2f, ExportEncoding.MIN_CRF, 40, "CRF %d")?.let { crf = it }
                    2 -> Widgets.doubleDrag("##size", targetMegabytes, 0.5f, "%.0f MB", 1f, 100000f)?.let { targetMegabytes = it }
                    else -> Widgets.doubleDrag("##bitrate", bitrateMbps, 0.2f, "%.1f Mbps", 0.5f, 400f)?.let { bitrateMbps = it }
                }
                if (modeIndex == 2) {
                    val kbps = ExportEncoding.bitrateForSize((targetMegabytes * 1_000_000).toLong(), buildSettings(session, start, end, null, draft = false))
                    Widgets.tooltip(String.format("about %.1f Mbps", kbps / 1000.0))
                }
                val accelerator = hardwareEncoder(backend)
                if (accelerator != null) {
                    Widgets.property("Encoder", "Hardware encoders are much faster; software gives slightly better quality per megabyte")
                    Widgets.segmented("hw", listOf("Software", accelerator), if (hardware) 1 else 0, 0f)?.let { hardware = it == 1 }
                }
                if (!hardware || accelerator == null) {
                    Widgets.property("Speed", "Slower presets squeeze more quality into the same size")
                    Widgets.segmented("encpreset", ENCODER_PRESETS, encoderPreset, 0f)?.let { encoderPreset = it }
                }
            }
        }
    }

    private fun hardwareEncoder(backend: ExportBackend): String? {
        if (format != ExportFormat.MP4_H264 && format != ExportFormat.MP4_H265) return null
        val name = backend.encoders().firstOrNull {
            it.endsWith("_nvenc") || it.endsWith("_amf") || it.endsWith("_qsv") || it.endsWith("_videotoolbox")
        } ?: return null
        return name.substringAfter('_').uppercase()
    }

    private fun audio() {
        if (Widgets.beginProperties("export-audio")) {
            Widgets.property("Game audio", "Renders the sounds the replay plays into the clip, positioned from the export camera. Sequences and GIFs get a .wav next to the frames.")
            Widgets.toggle("##gameaudio", gameAudio)?.let { gameAudio = it }
            if (gameAudio) {
                Widgets.property("Game volume")
                Widgets.doubleSlider("##gamevolume", gameAudioVolume, 0.0, 2.0, "%.2f")?.let { gameAudioVolume = it }
            }
            if (format.supportsAudio) {
                Widgets.property("Music", "Optional music or voice track mixed into the video; any format ffmpeg reads")
                ImGui.inputTextWithHint("##audio", "Path to an audio file", audioFile)
                val path = audioFile.get().trim()
                if (path.isNotBlank()) {
                    if (!Files.isRegularFile(Paths.get(path))) {
                        Widgets.property("")
                        Widgets.pill("file not found, music will be skipped", EditorTheme.WARNING)
                    }
                    Widgets.property("Offset", "Skip this far into the music before it starts")
                    Widgets.doubleDrag("##audiooffset", audioOffset, 0.1f, "%.2f s", 0f, 3600f)?.let { audioOffset = it }
                    Widgets.property("Music volume")
                    Widgets.doubleSlider("##audiovolume", audioVolume, 0.0, 2.0, "%.2f")?.let { audioVolume = it }
                }
            }
            Widgets.property("Fade", "Fade from and to black, and the audio with it")
            val half = EditorFonts.px(96f)
            ImGui.setNextItemWidth(half)
            Widgets.doubleDrag("##fadein", fadeIn, 0.05f, "in %.1f s", 0f, 30f)?.let { fadeIn = it }
            ImGui.sameLine(0f, EditorFonts.px(6f))
            ImGui.setNextItemWidth(half)
            Widgets.doubleDrag("##fadeout", fadeOut, 0.05f, "out %.1f s", 0f, 30f)?.let { fadeOut = it }
            Widgets.endProperties()
        }
        if (!format.supportsAudio) Widgets.smallText("${format.label} has no audio track. Game audio is still written as a .wav file.", EditorTheme.TEXT_DIM.u32)
    }

    private fun advanced(session: EditorSession) {
        Widgets.header("Rendering")
        if (Widgets.beginProperties("export-render")) {
            Widgets.property("Motion blur", "Averages several sub-frames per output frame. Render time scales with the sample count.")
            Widgets.segmented("blur", BLUR_LABELS, blurIndex, 0f)?.let { blurIndex = it }
            if (blurIndex > 0) {
                Widgets.property("Shutter", "180° is the film default; 360° blurs across the whole frame interval")
                Widgets.doubleSlider("##shutter", shutterDegrees, 45.0, 360.0, "%.0f°")?.let { shutterDegrees = it }
            }
            Widgets.property("Supersampling", "Renders at 2× or 4× and averages down for clean edges")
            Widgets.segmented("ssaa", SSAA_LABELS, supersampleIndex, 0f)?.let { supersampleIndex = it }
            Widgets.property("Projection", "360 and cube map render six views per frame, stereo renders two eyes side by side")
            Widgets.enumCombo("##projection", projection) { it.label }?.let { chooseProjection(it) }
            if (projection == ExportProjection.STEREO_SBS) {
                Widgets.property("Eye separation")
                Widgets.doubleSlider("##ipd", stereoSeparation, 0.02, 0.15, "%.3f m")?.let { stereoSeparation = it }
            }
            if (projection == ExportProjection.ORTHOGRAPHIC) {
                Widgets.property("Ortho scale", "Half-height of the view in blocks; smaller zooms in")
                Widgets.slider("##orthoscale", orthoScale, 0.5f, 200f, "%.1f")?.let { orthoScale = it }
            }
            if (format == ExportFormat.MP4_H264 || format == ExportFormat.MP4_H265) {
                Widgets.property("Chroma", "4:4:4 keeps full colour detail for sharp text, but many players cannot decode it and it always uses the software encoder")
                val note = if (highChroma) "software encoder, limited playback" else ""
                val noteWidth = if (note.isEmpty()) 0f else EditorFonts.with(EditorFonts.smallMedium) { Widgets.textWidth(note) } + EditorFonts.px(22f)
                Widgets.segmented("chroma", listOf("4:2:0", "4:4:4"), if (highChroma) 1 else 0, 0f, reserve = noteWidth)?.let { highChroma = it == 1 }
                if (note.isNotEmpty()) {
                    ImGui.sameLine(0f, EditorFonts.px(8f))
                    Widgets.pill(note, EditorTheme.WARNING)
                }
            }
            Widgets.property("Wait for chunks", "Hold each frame until every visible chunk has compiled so nothing pops in. Costs a few game frames when the camera moves fast.")
            Widgets.toggle("##waitchunks", waitForChunks)?.let { waitForChunks = it }
            Widgets.property("Speed & freeze", "Bake speed ramps and freeze frames into the output timing")
            Widgets.toggle("##timelanes", applyTimeLanes)?.let { applyTimeLanes = it }
            look(session)
            if (ExportEncoding.supportsDepth(format)) {
                Widgets.property(
                    "Depth map",
                    if (format.sequence) "Also write a 16-bit greyscale depth image per frame" else "Also write a greyscale depth video next to the export, named -depth, for depth of field or fog in your editor"
                )
                Widgets.toggle("##depth", depthMap)?.let { depthMap = it }
                if (depthMap) {
                    Widgets.property("Depth range", "Distance that maps to white. Auto uses the render distance; a shorter range keeps more precision up close.")
                    val custom = depthRange > 0.0
                    val field = EditorFonts.px(96f)
                    Widgets.segmented("depthrange", listOf("Auto", "Custom"), if (custom) 1 else 0, 0f, reserve = if (custom) field + EditorFonts.px(8f) else 0f)
                        ?.let { depthRange = if (it == 1) 64.0 else 0.0 }
                    if (custom) {
                        ImGui.sameLine(0f, EditorFonts.px(8f))
                        ImGui.setNextItemWidth(field)
                        Widgets.doubleDrag("##depthrangevalue", depthRange, 0.5f, "%.0f m", 1f, 1024f)?.let { depthRange = it.coerceIn(1.0, 1024.0) }
                    }
                }
            }
            if (format == ExportFormat.PNG_SEQUENCE || format == ExportFormat.WEBM_VP9 || format == ExportFormat.MOV_PRORES) {
                Widgets.property(
                    "Transparent sky",
                    "Leaves the sky and sun out and writes an alpha channel so the world can be composited over other footage. ProRes switches to the 4444 profile."
                )
                Widgets.toggle("##alpha", alpha)?.let {
                    alpha = it
                    if (it && format == ExportFormat.MOV_PRORES && proresProfile < ExportEncoding.PRORES_4444) proresProfile = ExportEncoding.PRORES_4444
                }
            }
            Widgets.endProperties()
        }
        Widgets.header("Files")
        if (Widgets.beginProperties("export-files")) {
            Widgets.property("Chapters", "Write a YouTube-style chapters text file from the markers inside the range")
            Widgets.toggle("##chapters", chapters)?.let { chapters = it }
            Widgets.property("When done", "Open the export folder once a render finishes")
            Widgets.toggle("open folder##opendone", context.ui.openFolderAfterExport)?.let { context.ui.openFolderAfterExport = it }
            Widgets.property("Folder")
            if (Widgets.iconButton("open-exports", Icon.FOLDER, ImGui.getFrameHeight(), "Open the export folder", color = EditorTheme.TEXT_MUTED.u32)) openFolder(context.host.exportsDirectory)
            ImGui.sameLine()
            ImGui.alignTextToFramePadding()
            Widgets.smallText(context.host.exportsDirectory.toString(), EditorTheme.TEXT_DIM.u32, clipToWidth = true)
            Widgets.endProperties()
        }
    }

    private fun look(session: EditorSession) {
        val look = session.project.look
        if (!look.active) return
        Widgets.property("Apply look", "Render the Look panel's depth of field, grade, vignette, letterbox and grain into the export")
        Widgets.toggle("##applylook", applyLook)?.let { applyLook = it }
        if (!applyLook) return
        Widgets.property("Look")
        Widgets.chips(ExportLabels.look(look))
    }

    private fun ffmpegStatus(backend: ExportBackend) {
        if (backend.ffmpegAvailable) return
        ImGui.spacing()
        val download = backend.queue().handles().filter { it.job.name == "download ffmpeg" }.maxByOrNull { it.startedAtNanos }
        when (download?.state) {
            ExportState.QUEUED, ExportState.RUNNING -> {
                val fraction = download.progress.toFloat()
                Widgets.pill("downloading ffmpeg", EditorTheme.ACCENT_TEXT)
                ImGui.sameLine()
                if (Widgets.ghostButton("Cancel")) download.cancel()
                Widgets.progress(fraction, -1f, "${(fraction * 100).toInt()}%")
                Widgets.smallText(download.detail.ifBlank { "connecting" }, EditorTheme.TEXT_DIM.u32, clipToWidth = true)
                return
            }

            ExportState.FAILED -> {
                Widgets.pill("download failed", EditorTheme.RECORD)
                ImGui.sameLine()
                if (Widgets.accentButton("Retry")) backend.downloadFfmpeg()
                Widgets.wrappedText(download.failure?.message ?: "Unknown error", EditorTheme.RECORD.u32)
                return
            }

            else -> Unit
        }
        Widgets.pill("ffmpeg not installed", EditorTheme.WARNING)
        ImGui.sameLine()
        if (backend.ffmpegDownloadSupported) {
            if (Widgets.accentButton("Download ffmpeg")) backend.downloadFfmpeg()
            Widgets.tooltip("Downloads the ffmpeg libraries for this system (about 35 MB) into the afterimage/ffmpeg folder")
        }
        Widgets.wrappedText("Videos need ffmpeg. Without it you can still export PNG or JPEG frames.", EditorTheme.TEXT_DIM.u32)
    }

    private fun presets() {
        Widgets.header("Quick presets")
        for ((index, preset) in quickPresets.withIndex()) {
            val pressed = Widgets.row("quick-$index", EditorFonts.px(44f), false) { x, y, _, _ ->
                val list = ImGui.getWindowDrawList()
                list.addText(EditorFonts.bodyMedium, ImGui.getFontSize(), x + EditorFonts.px(10f), y + EditorFonts.px(7f), EditorTheme.TEXT.u32, preset.name)
                list.addText(EditorFonts.small, EditorFonts.small.fontSize.toInt(), x + EditorFonts.px(10f), y + EditorFonts.px(25f), EditorTheme.TEXT_MUTED.u32, preset.description)
            }
            if (pressed) {
                applyQuickPreset(preset)
                dialog.section = VIDEO
            }
        }
        Widgets.header("Saved presets")
        val stored = presetNames()
        if (focusPresetName) {
            focusPresetName = false
            ImGui.setKeyboardFocusHere()
        }
        ImGui.setNextItemWidth(EditorFonts.px(240f))
        val submitted = ImGui.inputTextWithHint("##presetname", "Name the current settings", presetName, ImGuiInputTextFlags.EnterReturnsTrue)
        ImGui.sameLine()
        val name = presetName.get().trim()
        if (name.isEmpty()) ImGui.beginDisabled()
        if (Widgets.accentButton("Save") || (submitted && name.isNotEmpty())) savePreset(name, stored)
        if (name.isEmpty()) ImGui.endDisabled()
        if (stored.isEmpty()) {
            Widgets.smallText("Presets you save show up here and in the preset menu.", EditorTheme.TEXT_DIM.u32)
            return
        }
        ImGui.dummy(0f, EditorFonts.px(2f))
        if (ImGui.beginTable("saved-presets", 3, ImGuiTableFlags.RowBg or ImGuiTableFlags.BordersInnerH)) {
            ImGui.tableSetupColumn("name", ImGuiTableColumnFlags.WidthStretch)
            ImGui.tableSetupColumn("load", ImGuiTableColumnFlags.WidthFixed, Widgets.buttonWidth("Load", Widgets.ButtonStyle.GHOST))
            ImGui.tableSetupColumn("delete", ImGuiTableColumnFlags.WidthFixed, ImGui.getFrameHeight())
            for (saved in stored) {
                ImGui.pushID(saved)
                try {
                    ImGui.tableNextRow()
                    ImGui.tableNextColumn()
                    ImGui.alignTextToFramePadding()
                    ImGui.textUnformatted(saved)
                    if (appliedPreset?.first == saved) {
                        ImGui.sameLine()
                        Widgets.pill("current", EditorTheme.ACCENT_TEXT)
                    }
                    ImGui.tableNextColumn()
                    if (Widgets.ghostButton("Load")) loadSavedPreset(saved)
                    ImGui.tableNextColumn()
                    if (Widgets.iconButton("delete", Icon.TRASH, ImGui.getFrameHeight(), "Delete preset", color = EditorTheme.TEXT_MUTED.u32)) {
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

    private fun queue(backend: ExportBackend) {
        val handles = backend.queue().handles().sortedByDescending { it.startedAtNanos }
        if (handles.isEmpty()) {
            Widgets.emptyState("No exports yet", "Running and finished renders show up here", Icon.LIST)
            return
        }
        val finished = handles.filter { it.state != ExportState.RUNNING && it.state != ExportState.QUEUED }
        if (finished.isNotEmpty()) {
            Widgets.rightAlign(Widgets.buttonWidth("Clear finished", Widgets.ButtonStyle.GHOST))
            if (Widgets.ghostButton("Clear finished")) finished.forEach { backend.queue().forget(it.id) }
            ImGui.dummy(0f, EditorFonts.px(2f))
        }
        for (handle in handles) card(handle.id.toString()) { width -> job(backend, handle, width) }
    }

    private inline fun card(id: String, content: (width: Float) -> Unit) {
        val list = ImGui.getWindowDrawList()
        val pad = EditorFonts.px(12f)
        val gap = EditorFonts.px(8f)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val width = ImGui.getContentRegionAvailX()
        list.channelsSplit(2)
        list.channelsSetCurrent(1)
        ImGui.setCursorScreenPos(x + pad, y + pad)
        ImGui.pushID(id)
        ImGui.beginGroup()
        try {
            content(width - pad * 2f)
        } finally {
            ImGui.endGroup()
            ImGui.popID()
        }
        val bottom = ImGui.getItemRectMaxY() + pad
        list.channelsSetCurrent(0)
        list.addRectFilled(x, y, x + width, bottom, EditorTheme.PANEL_RAISED.u32, EditorFonts.px(8f))
        list.addRect(x, y, x + width, bottom, EditorTheme.BORDER_SOFT.u32, EditorFonts.px(8f))
        list.channelsMerge()
        ImGui.setCursorScreenPos(x, bottom)
        ImGui.dummy(width, gap)
    }

    private fun job(backend: ExportBackend, handle: ExportHandle, width: Float) {
        val state = handle.state
        val warnings = handle.warnings
        val name = handle.job.name.removePrefix("export ")
        val (label, color) = when {
            state == ExportState.RUNNING -> "rendering" to EditorTheme.ACCENT_TEXT
            state == ExportState.QUEUED -> "queued" to EditorTheme.CONTROL_ACTIVE
            state == ExportState.DONE && warnings.isNotEmpty() -> "done with warnings" to EditorTheme.WARNING
            state == ExportState.DONE -> "done" to EditorTheme.SUCCESS
            state == ExportState.FAILED -> "failed" to EditorTheme.RECORD
            else -> "cancelled" to EditorTheme.CONTROL_ACTIVE
        }
        Widgets.pill(label, color)
        ImGui.sameLine(0f, EditorFonts.px(8f))
        ImGui.alignTextToFramePadding()
        val meta = jobMeta(handle)
        val metaWidth = Widgets.chipsWidth(meta)
        EditorFonts.with(EditorFonts.bodyMedium) { ImGui.textUnformatted(Widgets.clip(name, width * 0.55f)) }
        if (meta.isNotEmpty()) {
            ImGui.sameLine(width - metaWidth)
            Widgets.chips(meta)
        }
        when (state) {
            ExportState.RUNNING, ExportState.QUEUED -> {
                val cancel = Widgets.buttonWidth("Cancel", Widgets.ButtonStyle.GHOST)
                Widgets.progress(handle.progress.toFloat(), width - cancel - EditorFonts.px(64f), String.format("%.0f%%", handle.progress * 100))
                ImGui.sameLine(width - cancel)
                if (Widgets.ghostButton("Cancel")) handle.cancel()
                val live = backend.live()?.takeIf { state == ExportState.RUNNING && handle.job.name.startsWith("export ") }
                when {
                    live != null -> {
                        Widgets.chips(stats.chips(System.nanoTime(), live, handle))
                        val stage = live.stage.takeIf { it.isNotBlank() } ?: handle.detail.takeIf { it.startsWith("cancel") }
                        if (stage != null) Widgets.smallText(stage, EditorTheme.WARNING.u32, clipToWidth = true)
                    }

                    state == ExportState.QUEUED -> Widgets.smallText("waiting for the current export to finish", EditorTheme.TEXT_DIM.u32)
                    else -> Widgets.smallText(handle.detail.ifBlank { "starting" }, EditorTheme.TEXT_DIM.u32, clipToWidth = true)
                }
            }

            ExportState.DONE -> {
                for (warning in warnings) {
                    Icons.inline(Icon.WARNING, EditorFonts.px(12f), EditorTheme.WARNING.u32)
                    ImGui.sameLine(0f, EditorFonts.px(5f))
                    Widgets.smallText(warning, EditorTheme.WARNING.u32, clipToWidth = true)
                }
                val path = handle.result
                if (path != null) {
                    val actions = ArrayList<Pair<String, () -> Unit>>(5)
                    if (!Files.isDirectory(path)) actions += "Play" to { play(path) }
                    singleImage(path)?.let { image -> actions += "Copy image" to { copyImage(image) } }
                    actions += "Show in folder" to { openFolder(path) }
                    actions += "Copy path" to { ImGui.setClipboardText(path.toAbsolutePath().toString()) }
                    actions += "Clear" to { backend.queue().forget(handle.id) }
                    Widgets.smallButtons(actions)
                }
            }

            ExportState.FAILED -> {
                Widgets.wrappedText(handle.failure?.message ?: "Unknown error", EditorTheme.RECORD.u32)
                Widgets.smallButtons(listOf("Clear" to { backend.queue().forget(handle.id) }))
            }

            ExportState.CANCELLED -> Widgets.smallButtons(listOf("Clear" to { backend.queue().forget(handle.id) }))
        }
    }

    private fun jobMeta(handle: ExportHandle): List<String> {
        if (handle.state != ExportState.DONE) return emptyList()
        val parts = ArrayList<String>(2)
        if (handle.finishedAtNanos > handle.startedAtNanos) parts += ExportLabels.clock(handle.finishedAtNanos - handle.startedAtNanos)
        val result = handle.result
        if (result != null) {
            val size = sizes.getOrPut(handle.id) { fileSize(result) }
            if (size > 0L) parts += ExportLabels.size(size)
        }
        return parts
    }

    private fun fileSize(path: Path): Long = runCatching {
        if (Files.isDirectory(path)) Files.list(path).use { stream -> stream.mapToLong { Files.size(it) }.sum() } else Files.size(path)
    }.getOrDefault(0L)

    private fun presetLabel(): String {
        val applied = appliedPreset ?: return "Custom"
        return if (applied.second == encodePreset()) applied.first else "Custom"
    }

    private fun applyQuickPreset(preset: QuickPreset) {
        preset.apply()
        appliedPreset = preset.name to encodePreset()
        context.status("Applied the ${preset.name} preset")
    }

    private fun loadSavedPreset(name: String) {
        context.host.preference("export.preset." + key(name))?.let { loadPreset(it) }
        appliedPreset = name to encodePreset()
        presetName.set(name)
        context.status("Loaded export preset $name")
    }

    private fun savePreset(name: String, stored: List<String>) {
        context.host.setPreference("export.preset." + key(name), encodePreset())
        context.host.setPreference("export.presetNames", (stored + name).distinct().joinToString("|"))
        appliedPreset = name to encodePreset()
        context.status("Saved export preset $name")
    }

    private fun chooseFormat(value: ExportFormat) {
        format = value
        if (value == ExportFormat.GIF && fps > 30) chooseFps(24)
    }

    private fun chooseFps(value: Int) {
        fps = value
        context.timeline.renderFps = value
    }

    private fun chooseProjection(value: ExportProjection) {
        projection = value
        when (value) {
            ExportProjection.EQUIRECTANGULAR -> {
                sizeIndex = CUSTOM
                width = maxOf(width, 4096)
                height = width / 2
            }

            ExportProjection.CUBE_MAP -> {
                sizeIndex = CUSTOM
                height = width / 3 * 2
            }

            else -> Unit
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
        "ssaa=${SSAA_FACTORS[supersampleIndex]}",
        "blur=$blurIndex",
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
        "alpha=$alpha",
        "depth=$depthMap",
        "depthRange=$depthRange",
        "look=$applyLook",
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
                    "crf" -> crf = v.toInt().coerceIn(ExportEncoding.MIN_CRF, 40)
                    "bitrate" -> bitrateMbps = v.toDouble()
                    "mb" -> targetMegabytes = v.toDouble()
                    "sizeMode" -> sizeMode = v.toBoolean()
                    "hw" -> hardware = v.toBoolean()
                    "preset" -> encoderPreset = v.toInt().coerceIn(0, 2)
                    "chroma" -> highChroma = v.toBoolean()
                    "projection" -> projection = ExportProjection.valueOf(v)
                    "orthoScale" -> orthoScale = v.toFloat()
                    "ssaa" -> supersampleIndex = v.toInt().let { if (it >= 4) 2 else if (it >= 2) 1 else 0 }
                    "blur" -> blurIndex = v.toInt().coerceIn(0, BLUR_LABELS.size - 1)
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
                    "alpha" -> alpha = v.toBoolean()
                    "depth" -> depthMap = v.toBoolean()
                    "depthRange" -> depthRange = v.toDouble()
                    "look" -> applyLook = v.toBoolean()
                }
            }
        }
    }

    private fun mapping(session: EditorSession, start: Long, end: Long): TimeMapping? =
        if (applyTimeLanes) ProjectTimeMapping.build(session.project, start, end) else null

    private fun buildSettings(session: EditorSession, start: Long, end: Long, mapping: TimeMapping?, draft: Boolean): ExportSettings {
        val name = ClipActions.safeName(fileName.get().ifBlank { defaultFileName(session) }) + if (draft) "-draft" else ""
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
            supersample = if (draft) 1 else SSAA_FACTORS[supersampleIndex],
            depthMap = depthMap && ExportEncoding.supportsDepth(format) && !draft,
            depthRange = depthRange,
            alpha = alpha && !draft,
            look = if (applyLook && !draft) session.project.look.copy() else null,
            projection = projection,
            stereoSeparation = stereoSeparation,
            orthoScale = orthoScale,
            timeMapping = mapping,
            audioFile = audioFile.get().trim().takeIf { it.isNotBlank() && format.supportsAudio }?.let { Paths.get(it) }?.takeIf { Files.isRegularFile(it) },
            audioOffsetSeconds = audioOffset,
            audioVolume = audioVolume,
            format = format,
            qualityMode = if (draft) QualityMode.CRF else qualityMode,
            hardware = hardware,
            motionBlur = if (draft || blurIndex == 0) MotionBlur.OFF else MotionBlur(BLUR_SAMPLES[blurIndex], shutterDegrees / 360.0),
            gifColors = gifColors,
            jpegQuality = jpegQuality,
            proresProfile = if (alpha && !draft && format == ExportFormat.MOV_PRORES) maxOf(proresProfile, ExportEncoding.PRORES_4444) else proresProfile,
            waitForChunks = waitForChunks,
            gameAudio = gameAudio,
            gameAudioVolume = gameAudioVolume,
            fadeInSeconds = if (draft) 0.0 else fadeIn,
            fadeOutSeconds = if (draft) 0.0 else fadeOut,
        )
        if (base.qualityMode != QualityMode.BITRATE) return base
        val kbps = if (sizeMode) ExportEncoding.bitrateForSize((targetMegabytes * 1_000_000).toLong(), base) else (bitrateMbps * 1000).toInt()
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
        val proxyFormat = if (backend.ffmpegAvailable) ExportFormat.MP4_H264 else ExportFormat.JPEG_SEQUENCE
        val exportSettings = ExportSettings(
            width = 1280,
            height = 720,
            fps = 30,
            startNanos = 0L,
            endNanos = replay.durationNanos,
            output = context.host.exportsDirectory.resolve("$name.${proxyFormat.extension}"),
            crf = 26,
            preset = "fast",
            format = proxyFormat,
            hardware = true,
        )
        val handle = backend.submit(ExportRequest(name, exportSettings, if (proxyFormat.needsFfmpeg) ExportTarget.VIDEO else ExportTarget.PNG_SEQUENCE))
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
            if (current.muted != previousMuted) session.execute(SetLaneState(LaneKind.CAMERA, current.copy(muted = previousMuted)))
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
        val settings = buildSettings(session, start, end, mapping(session, start, end), draft)
        val target = if (format.needsFfmpeg) ExportTarget.VIDEO else ExportTarget.PNG_SEQUENCE
        val handle = backend.submit(ExportRequest(settings.output.fileName.toString().substringBeforeLast('.'), settings, target))
        if (chapters && !draft) writeChapters(session, settings, start, end)
        context.host.setPreference("export.last", encodePreset())
        context.status(if (handle != null) "Export queued: ${settings.output.fileName}" else "Could not queue the export")
    }

    private fun exportAllClips(backend: ExportBackend, session: EditorSession) {
        var queued = 0
        for (clip in session.project.clips.sortedBy { it.startNanos }) {
            val base = buildSettings(session, clip.startNanos, clip.endNanos, mapping(session, clip.startNanos, clip.endNanos), draft = false)
            val safe = ClipActions.safeName(clip.title)
            val settings = base.copy(output = context.host.exportsDirectory.resolve("$safe.${format.extension}"))
            backend.submit(ExportRequest(safe, settings, if (format.needsFfmpeg) ExportTarget.VIDEO else ExportTarget.PNG_SEQUENCE))
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
        val path = settings.output.resolveSibling(settings.output.fileName.toString().substringBeforeLast('.') + ".chapters.txt")
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
            supersample = SSAA_FACTORS[supersampleIndex],
            depthMap = depthMap,
            depthRange = depthRange,
            alpha = alpha,
            look = if (applyLook) session.project.look.copy() else null,
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
            if (!handle.job.name.startsWith("export ")) continue
            val size = sizes.getOrPut(handle.id) { fileSize(result) }
            val warnings = handle.warnings
            if (warnings.isEmpty()) context.status("Export finished: ${result.fileName}    ${ExportLabels.size(size)}")
            else context.toast("${result.fileName} finished with ${warnings.size} warning${if (warnings.size == 1) "" else "s"}", EditorTheme.WARNING)
            if (context.ui.openFolderAfterExport) openFolder(result)
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
                stream.filter { it.fileName.toString().endsWith(".png") && !it.fileName.toString().contains("-depth") }.toList()
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
        runCatching { java.awt.Desktop.getDesktop().open(path.toFile()) }
            .onFailure { context.status("Could not open: ${it.message}") }
    }

    private fun resolveRange(session: EditorSession, duration: Long): Pair<Long, Long> {
        val inOut = session.project.inPointNanos to (if (session.project.outPointNanos > 0L) session.project.outPointNanos else duration)
        return when (range) {
            Range.WHOLE -> 0L to duration
            Range.CLIP -> session.selection.clipIds.firstOrNull()?.let { session.project.clip(it) }?.let { it.startNanos to it.endNanos } ?: inOut
            Range.IN_OUT -> inOut
        }
    }

    private fun defaultFileName(session: EditorSession): String {
        val base = session.project.recording.fileName.toString().substringBeforeLast('.')
        return when (range) {
            Range.CLIP -> session.selection.clipIds.firstOrNull()?.let { session.project.clip(it) }?.let { ClipActions.safeName(it.title) } ?: base
            else -> base
        }
    }

    private val quickPresets: List<QuickPreset> = listOf(
        QuickPreset("YouTube", "1080p at 60 fps, H.264 CRF 16, slow encoder preset") {
            format = ExportFormat.MP4_H264
            sizeIndex = 1
            width = 1920
            height = 1080
            chooseFps(60)
            qualityMode = QualityMode.CRF
            sizeMode = false
            crf = 16
            highChroma = false
            encoderPreset = 2
        },
        QuickPreset("Discord", "1080p at 60 fps, sized to fit a 25 MB upload") {
            format = ExportFormat.MP4_H264
            sizeIndex = 1
            width = 1920
            height = 1080
            chooseFps(60)
            qualityMode = QualityMode.BITRATE
            sizeMode = true
            targetMegabytes = 24.0
            encoderPreset = 1
        },
        QuickPreset("Shorts", "Vertical 1080 × 1920 at 60 fps, CRF 18") {
            format = ExportFormat.MP4_H264
            sizeIndex = SIZES.indexOfFirst { it.name == "Vertical 1080" }
            width = 1080
            height = 1920
            chooseFps(60)
            qualityMode = QualityMode.CRF
            sizeMode = false
            crf = 18
        },
        QuickPreset("Edit master", "ProRes HQ 1080p with 2× supersampling for further editing") {
            format = ExportFormat.MOV_PRORES
            proresProfile = 3
            sizeIndex = 1
            width = 1920
            height = 1080
            supersampleIndex = 1
        },
        QuickPreset("GIF", "720p at 24 fps with a 128 colour palette") {
            format = ExportFormat.GIF
            sizeIndex = SIZES.indexOfFirst { it.name == "720p" }
            width = 1280
            height = 720
            chooseFps(24)
            gifColors = 128
        },
    )

    private companion object {
        const val VIDEO = 0
        const val AUDIO = 1
        const val PRESETS = 3
        const val QUEUE = 4
        val SECTIONS = listOf(
            Dialog.Section("Video", Icon.MONITOR),
            Dialog.Section("Audio", Icon.VOLUME),
            Dialog.Section("Advanced", Icon.SLIDERS),
            Dialog.Section("Presets", Icon.BOOKMARK),
            Dialog.Section("Queue", Icon.LIST),
        )
        val SIZES = listOf(
            Size("720p", 1280, 720),
            Size("1080p", 1920, 1080),
            Size("1440p", 2560, 1440),
            Size("4K", 3840, 2160),
            Size("8K", 7680, 4320),
            Size("Vertical 1080", 1080, 1920),
            Size("Vertical 4K", 2160, 3840),
            Size("Square", 1080, 1080),
            Size("Ultrawide", 2560, 1080),
            Size("Ultrawide 1440", 3440, 1440),
            Size("Cinemascope", 1920, 804),
            Size("Custom", 0, 0),
        )
        val CUSTOM = SIZES.size - 1
        val FPS = listOf(24, 30, 60, 120)
        val QUALITY_MODES = listOf("Quality", "Bitrate", "File size")
        val ENCODER_PRESETS = listOf("Fast", "Medium", "Slow")
        val BLUR_LABELS = listOf("Off", "2", "4", "8", "16")
        val BLUR_SAMPLES = intArrayOf(1, 2, 4, 8, 16)
        val SSAA_LABELS = listOf("Off", "2×", "4×")
        val SSAA_FACTORS = intArrayOf(1, 2, 4)
        val PRORES = listOf("Proxy" to 0, "LT" to 1, "Standard" to 2, "HQ" to 3, "4444" to 4)
    }
}
