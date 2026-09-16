package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.editor.EditorSession
import gg.sona.afterimage.editor.ValueLane
import gg.sona.afterimage.editor.commands.SetLaneState
import gg.sona.afterimage.editor.commands.SetValueKeyframe
import imgui.ImGui
import imgui.flag.ImGuiColorEditFlags

class VisualsPanel(private val context: EditorContext) : AbstractPanel("Visuals", DockArea.RIGHT, Icon.EYE) {

    private val colorHolder = FloatArray(3)

    override fun content(frame: FrameContext) {
        val visuals = context.visuals
        val session = context.session
        val hidden = visuals.hiddenEntities.size
        val hiddenTypes = visuals.hiddenEntityTypes.size + visuals.hiddenParticleTypes.size
        if (Widgets.ghostButton("Render filter")) context.openPanel("Render Filter")
        Widgets.tooltip("Hide entity and particle types one by one")
        if (hiddenTypes > 0) {
            ImGui.sameLine(0f, EditorFonts.px(6f))
            Widgets.chip("$hiddenTypes", EditorTheme.WARNING.u32, EditorTheme.WARNING.u32(0.16f))
        }
        ImGui.sameLine()
        Widgets.rightAlign(Widgets.buttonWidth("Reset all", Widgets.ButtonStyle.GHOST))
        if (Widgets.ghostButton("Reset all")) visuals.reset()
        Widgets.tooltip("Show everything again and drop every override")

        if (section("Interface", "visuals.gui")) {
            if (Widgets.beginProperties("gui")) {
                row("Hotbar", visuals.showHotbar) { visuals.showHotbar = it }
                row("Health, food, armor", visuals.showStatusBars) { visuals.showStatusBars = it }
                row("Experience bar", visuals.showExperience) { visuals.showExperience = it }
                row("Chat", visuals.showChat) { visuals.showChat = it }
                row("Scoreboard", visuals.showScoreboard) { visuals.showScoreboard = it }
                row("Boss bar", visuals.showBossBar) { visuals.showBossBar = it }
                row("Action bar", visuals.showActionBar) { visuals.showActionBar = it }
                row("Titles", visuals.showTitles) { visuals.showTitles = it }
                row("Vignette", visuals.showVignette) { visuals.showVignette = it }
                Widgets.endProperties()
            }
        }
        if (section("World", "visuals.world", trailing = if (hidden > 0) "$hidden hidden" else null)) {
            if (Widgets.beginProperties("world")) {
                row("Players", visuals.renderPlayers) { visuals.renderPlayers = it }
                row("Other entities", visuals.renderEntities) { visuals.renderEntities = it }
                row("Dropped items", visuals.renderItemsOnGround) { visuals.renderItemsOnGround = it }
                row("Name tags", visuals.renderNametags) { visuals.renderNametags = it }
                row("Particles", visuals.renderParticles) { visuals.renderParticles = it }
                row("Sky", visuals.renderSky) { visuals.renderSky = it }
                row("Clouds", visuals.renderClouds) { visuals.renderClouds = it }
                row("Rain and snow", visuals.renderWeather) { visuals.renderWeather = it }
                row("Entity shadows", visuals.renderShadows) { visuals.renderShadows = it }
                row("Hitboxes", visuals.renderHitboxes) { visuals.renderHitboxes = it }
                if (hidden > 0) {
                    Widgets.property("Hidden entities", "Entities hidden one by one from the Hierarchy or the Inspector")
                    Widgets.chip("$hidden")
                    ImGui.sameLine(0f, EditorFonts.px(6f))
                    if (Widgets.smallButton("Show all")) visuals.hiddenEntities.clear()
                }
                Widgets.endProperties()
            }
        }
        if (section("Overrides", "visuals.overrides")) {
            if (Widgets.beginProperties("overrides")) {
                row("Time of day", visuals.overrideTime) { visuals.overrideTime = it }
                if (visuals.overrideTime) {
                    Widgets.property("Time")
                    Widgets.slider("##time", visuals.timeOfDay.toFloat(), 0f, 23999f, labelOf = { timeLabel(it.toInt()) })
                        ?.let { visuals.timeOfDay = it.toLong() }
                    quickKeyframeButton(session, ValueLane.TIME_OF_DAY, visuals.timeOfDay.toDouble())
                    Widgets.property("Presets")
                    Widgets.buttonGroup("time-presets", TIME_PRESET_LABELS)?.let { visuals.timeOfDay = TIME_PRESETS[it] }
                }
                Widgets.property("Weather")
                Widgets.enumCombo("##weather", visuals.weather) { it.label }?.let { visuals.weather = it }
                row("Night vision", visuals.nightVision) { visuals.nightVision = it }
                Widgets.property("Brightness boost")
                Widgets.slider("##boost", visuals.brightnessBoost, 0f, 1f, "%.2f")?.let { visuals.brightnessBoost = it }
                row("Fog distance", visuals.overrideFog) { visuals.overrideFog = it }
                if (visuals.overrideFog) {
                    Widgets.property("Fog start")
                    Widgets.slider("##fogstart", visuals.fogStart, 0f, 1f, "%.2f")
                        ?.let { visuals.fogStart = minOf(it, visuals.fogEnd) }
                    Widgets.property("Fog end")
                    Widgets.slider("##fogend", visuals.fogEnd, 0.05f, 2f, "%.2f")
                        ?.let { visuals.fogEnd = maxOf(it, visuals.fogStart) }
                }
                row("Fog colour", visuals.overrideFogColor) { visuals.overrideFogColor = it }
                if (visuals.overrideFogColor) {
                    Widgets.property("Colour")
                    colorEdit("##fogcolor", visuals.fogColor)?.let { visuals.fogColor = it }
                }
                row("Sky colour", visuals.overrideSkyColor) { visuals.overrideSkyColor = it }
                if (visuals.overrideSkyColor) {
                    Widgets.property("Colour", "Disable Sky and set a solid colour for chroma keying")
                    colorEdit("##skycolor", visuals.skyColor)?.let { visuals.skyColor = it }
                }
                Widgets.endProperties()
            }
        }
        if (section("Guides", "visuals.guides")) {
            if (Widgets.beginProperties("guides")) {
                row("Center guide", visuals.centerGuide) { visuals.centerGuide = it }
                row("Rule of thirds", visuals.thirdsGuide) { visuals.thirdsGuide = it }
                row("Real-time clock", visuals.rtcOverlay) { visuals.rtcOverlay = it }
                Widgets.endProperties()
            }
        }
    }

    private fun section(title: String, key: String, trailing: String? = null): Boolean =
        Widgets.foldout(title, key, context.ui.collapsedSections, true, trailing)

    private inline fun row(label: String, value: Boolean, apply: (Boolean) -> Unit) {
        Widgets.property(label)
        Widgets.toggle("##$label", value)?.let(apply)
    }

    private fun quickKeyframeButton(session: EditorSession?, lane: ValueLane, value: Double) {
        if (session == null) return
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
        }
    }

    private fun colorEdit(id: String, rgb: Int): Int? {
        colorHolder[0] = ((rgb shr 16) and 0xFF) / 255f
        colorHolder[1] = ((rgb shr 8) and 0xFF) / 255f
        colorHolder[2] = (rgb and 0xFF) / 255f
        if (!ImGui.colorEdit3(id, colorHolder, ImGuiColorEditFlags.NoInputs or ImGuiColorEditFlags.NoLabel)) return null
        return ((colorHolder[0] * 255f).toInt().coerceIn(0, 255) shl 16) or ((colorHolder[1] * 255f).toInt()
            .coerceIn(0, 255) shl 8) or (colorHolder[2] * 255f).toInt().coerceIn(0, 255)
    }

    private fun timeLabel(ticks: Int): String {
        val hours = ((ticks / 1000 + 6) % 24)
        val minutes = ((ticks % 1000) * 60 / 1000)
        return String.format("%02d:%02d  (%d)", hours, minutes, ticks)
    }

    private companion object {
        val TIME_PRESETS = longArrayOf(23000L, 6000L, 12000L, 18000L)
        val TIME_PRESET_LABELS = listOf("Dawn", "Noon", "Dusk", "Midnight")
    }
}
