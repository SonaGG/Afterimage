package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.camera.CameraSettings
import gg.sona.afterimage.editor.EditorSession
import gg.sona.afterimage.editor.LaneKind
import gg.sona.afterimage.editor.ValueLane
import gg.sona.afterimage.editor.commands.SetLaneState
import gg.sona.afterimage.editor.commands.SetLook
import gg.sona.afterimage.editor.commands.SetValueKeyframe
import gg.sona.afterimage.editor.look.LookSettings
import gg.sona.afterimage.world.EntityKind
import imgui.ImGui
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList

class LookPanel(private val context: EditorContext) : AbstractPanel("Look", DockArea.RIGHT, Icon.PALETTE) {

    private var luts: List<String> = emptyList()
    private var lutsScannedNanos = 0L

    override fun content(frame: FrameContext) {
        val session = context.session
        if (session == null) {
            Widgets.emptyState("No replay open", "Open a recording to grade it", Icon.PALETTE)
            return
        }
        val look = session.project.look
        Widgets.toggle("Preview in viewport##preview", context.ui.lookPreview)?.let { context.ui.lookPreview = it }
        Widgets.tooltip("Show the look on the world while editing; gizmos, the hand and the HUD stay untouched. Exports render it when Apply look is on in the Export panel.")
        if (look.active) {
            ImGui.sameLine()
            Widgets.rightAlign(Widgets.buttonWidth("Reset", Widgets.ButtonStyle.GHOST))
            if (Widgets.ghostButton("Reset")) session.execute(SetLook("reset", LookSettings()))
            Widgets.tooltip("Back to the untouched image")
        }
        depthOfField(session, look)
        grade(session, look)
        finish(session, look)
        ImGui.dummy(0f, EditorFonts.px(4f))
        Widgets.wrappedText(
            "360 exports skip the look. Depth of field needs the perspective or orthographic projection.",
            EditorTheme.TEXT_DIM.u32
        )
    }

    private fun section(title: String, key: String, trailing: String? = null): Boolean =
        Widgets.foldout(title, key, context.ui.collapsedSections, true, trailing)

    private fun depthOfField(session: EditorSession, look: LookSettings) {
        if (!section("Depth of field", "look.dof", if (look.depthOfField) "On" else "Off")) return
        if (Widgets.beginProperties("look-dof")) {
            Widgets.property("Enabled", "Blurs everything away from the focus distance, like a real lens")
            Widgets.toggle("##dof", look.depthOfField)?.let { value -> edit(session, "dof") { it.depthOfField = value } }
            if (look.depthOfField) {
                Widgets.property("Focus on", "Follow an entity, or set the distance by hand and keyframe it on the Focus lane")
                focusPicker(session, look)
                if (look.focusTargetId == null) {
                    Widgets.property("Focus distance")
                    Widgets.doubleSlider("##focus", look.focusDistance, ValueLane.FOCUS.min, 64.0, "%.1f m")
                        ?.let { value -> edit(session, "focus") { it.focusDistance = value } }
                    quickKeyframeButton(session, ValueLane.FOCUS, look.focusDistance)
                    val keyed = session.valueAt(ValueLane.FOCUS, session.playheadNanos)
                    if (keyed != null) {
                        Widgets.property("At playhead", "The Focus lane overrides the slider while it has keyframes")
                        Widgets.chips(listOf("Focus lane", ValueLane.FOCUS.format(keyed)))
                    }
                } else {
                    Widgets.property("Distance now")
                    val distance = session.focusDistanceAt(session.playheadNanos, context.host.camera.currentPose().position)
                    Widgets.chip(ValueLane.FOCUS.format(distance))
                }
                Widgets.property("Aperture", "How strong the blur gets away from focus")
                Widgets.doubleSlider("##aperture", look.aperture, 0.0, 1.0, "%.2f")
                    ?.let { value -> edit(session, "aperture") { it.aperture = value } }
                Widgets.property("Focus range", "Distance around the focus point that stays sharp")
                Widgets.doubleSlider("##range", look.focusRange, 0.0, 8.0, "%.1f m")
                    ?.let { value -> edit(session, "focusRange") { it.focusRange = value } }
            }
            Widgets.endProperties()
        }
    }

    private fun focusPicker(session: EditorSession, look: LookSettings) {
        val shadow = context.replay?.world
        val recorderName = shadow?.localPlayer?.name ?: "Recorder"
        val current = look.focusTargetId
        val label = when (current) {
            null -> "Focus distance"
            CameraSettings.TARGET_RECORDER -> recorderName
            else -> entityLabel(current)
        }
        if (Widgets.popupButton("focus-target", label)) {
            if (Menus.item("Focus distance", "", current == null)) edit(session, "focusTarget") { it.focusTargetId = null }
            if (Menus.item(recorderName, "", current == CameraSettings.TARGET_RECORDER)) edit(session, "focusTarget") {
                it.focusTargetId = CameraSettings.TARGET_RECORDER
            }
            val players = shadow?.entities?.values()?.filter { it.kind == EntityKind.PLAYER }?.sortedBy { entityLabel(it.id) }
                ?: emptyList()
            if (players.isNotEmpty()) ImGui.separator()
            for (entity in players) {
                if (Menus.item(entityLabel(entity.id), "", current == entity.id)) edit(session, "focusTarget") {
                    it.focusTargetId = entity.id
                }
            }
            Widgets.endPopup()
        }
    }

    private fun grade(session: EditorSession, look: LookSettings) {
        if (!section("Grade", "look.grade", if (look.lut.isEmpty()) null else look.lut.removeSuffix(".cube").removeSuffix(".CUBE"))) return
        if (Widgets.beginProperties("look-grade")) {
            Widgets.property("Exposure", "Stops of brightness")
            Widgets.doubleSlider("##exposure", look.exposure, -3.0, 3.0, "%+.2f EV")
                ?.let { value -> edit(session, "exposure") { it.exposure = value } }
            Widgets.property("Contrast")
            Widgets.doubleSlider("##contrast", look.contrast, 0.5, 2.0, "%.2f")
                ?.let { value -> edit(session, "contrast") { it.contrast = value } }
            Widgets.property("Saturation")
            Widgets.doubleSlider("##saturation", look.saturation, 0.0, 2.0, "%.2f")
                ?.let { value -> edit(session, "saturation") { it.saturation = value } }
            Widgets.property("LUT", "Colour lookup table (.cube) from the afterimage/luts folder")
            lutPicker(session, look)
            if (look.lut.isNotEmpty()) {
                Widgets.property("LUT strength")
                Widgets.doubleSlider("##lutstrength", look.lutStrength, 0.0, 1.0, "%.2f")
                    ?.let { value -> edit(session, "lutStrength") { it.lutStrength = value } }
            }
            Widgets.endProperties()
        }
    }

    private fun lutPicker(session: EditorSession, look: LookSettings) {
        val label = if (look.lut.isEmpty()) "None" else look.lut.removeSuffix(".cube").removeSuffix(".CUBE")
        if (Widgets.popupButton("lut", label)) {
            val files = scanLuts()
            if (Menus.item("None", "", look.lut.isEmpty())) edit(session, "lut") { it.lut = "" }
            if (files.isNotEmpty()) ImGui.separator()
            for (file in files) {
                if (Menus.item(file.removeSuffix(".cube").removeSuffix(".CUBE"), "", file == look.lut)) edit(session, "lut") {
                    it.lut = file
                }
            }
            if (files.isEmpty()) Widgets.mutedText("Drop .cube files into afterimage/luts")
            ImGui.separator()
            if (Menus.item("Open LUT folder")) openFolder(context.host.lutsDirectory)
            if (Menus.item("Rescan")) lutsScannedNanos = 0L
            Widgets.endPopup()
        }
    }

    private fun scanLuts(): List<String> {
        val now = System.nanoTime()
        if (now - lutsScannedNanos < RESCAN_NANOS) return luts
        lutsScannedNanos = now
        val directory = context.host.lutsDirectory
        luts = runCatching {
            Files.createDirectories(directory)
            Files.list(directory).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".cube") }
                    .map { it.fileName.toString() }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
        return luts
    }

    private fun finish(session: EditorSession, look: LookSettings) {
        if (!section("Finish", "look.finish")) return
        if (Widgets.beginProperties("look-finish")) {
            Widgets.property("Vignette", "Darkens the corners")
            Widgets.doubleSlider("##vignette", look.vignette, 0.0, 1.0, "%.2f")
                ?.let { value -> edit(session, "vignette") { it.vignette = value } }
            if (look.vignette > 0.0) {
                Widgets.property("Vignette softness")
                Widgets.doubleSlider("##vignettesoft", look.vignetteSoftness, 0.05, 1.0, "%.2f")
                    ?.let { value -> edit(session, "vignetteSoftness") { it.vignetteSoftness = value } }
            }
            Widgets.property("Letterbox", "Black bars for a wider cinematic aspect ratio")
            val presets = LookSettings.LETTERBOX_PRESETS
            val selected = presets.indexOfFirst { Math.abs(it.second - look.letterbox) < 0.001 }
            Widgets.segmented("letterbox", presets.map { it.first }, selected, 0f)
                ?.let { index -> edit(session, "letterbox") { it.letterbox = presets[index].second } }
            Widgets.property("Film grain", "Animated noise, stronger in the shadows")
            Widgets.doubleSlider("##grain", look.grain, 0.0, 1.0, "%.2f")
                ?.let { value -> edit(session, "grain") { it.grain = value } }
            if (look.grain > 0.0) {
                Widgets.property("Grain size")
                Widgets.doubleSlider("##grainsize", look.grainSize, 1.0, 4.0, "%.1f px")
                    ?.let { value -> edit(session, "grainSize") { it.grainSize = value } }
            }
            Widgets.endProperties()
        }
    }

    private inline fun edit(session: EditorSession, field: String, mutate: (LookSettings) -> Unit) {
        val next = session.project.look.copy()
        mutate(next)
        session.execute(SetLook(field, next))
    }

    private fun quickKeyframeButton(session: EditorSession, lane: ValueLane, value: Double) {
        ImGui.sameLine()
        if (Widgets.iconButton(
                "qkf-${lane.name}",
                Icon.PLUS,
                ImGui.getFrameHeight(),
                "Add ${lane.label.lowercase()} keyframe here with this value",
                iconScale = 0.6f
            )
        ) {
            session.execute(SetValueKeyframe(lane, session.playheadNanos, value.coerceIn(lane.min, lane.max)))
            val state = session.project.lane(lane.kind)
            if (state.muted) session.execute(SetLaneState(lane.kind, state.copy(muted = false)))
            context.timeline.shownLanes.add(LaneKind.FOCUS)
        }
    }

    private fun entityLabel(id: Int): String {
        val shadow = context.replay?.world ?: return "#$id"
        val entity = shadow.entities[id] ?: return "#$id"
        val name = entity.uuid?.let { shadow.players.profile(it)?.name }
        return name ?: "${entity.kind.name.lowercase().replace('_', ' ')} #$id"
    }

    private fun openFolder(path: Path) {
        runCatching {
            Files.createDirectories(path)
            java.awt.Desktop.getDesktop().open(path.toFile())
        }.onFailure { context.status("Could not open folder: ${it.message}") }
    }

    private companion object {
        const val RESCAN_NANOS = 3_000_000_000L
    }
}
