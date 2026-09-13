package gg.sona.recast.editor.imgui

import gg.sona.recast.editor.EditorSession
import gg.sona.recast.editor.ValueLane
import gg.sona.recast.editor.commands.SetLaneState
import gg.sona.recast.editor.commands.SetValueKeyframe
import imgui.ImGui
import imgui.flag.ImGuiColorEditFlags

class VisualsPanel(private val context: EditorContext) : AbstractPanel("Visuals", DockArea.RIGHT, Icon.EYE) {

    private val colorHolder = FloatArray(3)

    override fun content(frame: FrameContext) {
        val visuals = context.visuals
        val session = context.session
        if (Widgets.ghostButton("Reset all")) visuals.reset()
        Widgets.header("Interface")
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
        Widgets.header("World")
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
            Widgets.endProperties()
        }
        val hidden = visuals.hiddenEntities.size
        if (hidden > 0) {
            Widgets.smallText("$hidden entities hidden individually")
            ImGui.sameLine()
            if (Widgets.smallButton("Unhide all")) visuals.hiddenEntities.clear()
        }
        val hiddenTypes = visuals.hiddenEntityTypes.size + visuals.hiddenParticleTypes.size
        if (Widgets.ghostButton(if (hiddenTypes > 0) "Render Filter ($hiddenTypes)" else "Render Filter...")) context.openPanel(
            "Render Filter"
        )

        Widgets.header("Overrides")
        if (Widgets.beginProperties("overrides")) {
            row("Override time of day", visuals.overrideTime) { visuals.overrideTime = it }
            if (visuals.overrideTime) {
                Widgets.property("Time of day")
                Widgets.slider("##time", visuals.timeOfDay.toFloat(), 0f, 23999f, labelOf = { timeLabel(it.toInt()) })
                    ?.let { visuals.timeOfDay = it.toLong() }
                quickKeyframeButton(session, ValueLane.TIME_OF_DAY, visuals.timeOfDay.toDouble())
                Widgets.property("")
                Widgets.segmented("time-presets", listOf("Dawn", "Noon", "Dusk", "Midnight"), -1, 0f)
                    ?.let { visuals.timeOfDay = TIME_PRESETS[it] }
            }
            Widgets.property("Weather")
            Widgets.enumCombo("##weather", visuals.weather) { it.label }?.let { visuals.weather = it }
            row("Night vision", visuals.nightVision) { visuals.nightVision = it }
            Widgets.property("Brightness boost")
            Widgets.slider("##boost", visuals.brightnessBoost, 0f, 1f, "%.2f")?.let { visuals.brightnessBoost = it }
            row("Override fog distance", visuals.overrideFog) { visuals.overrideFog = it }
            if (visuals.overrideFog) {
                Widgets.property("Fog start")
                Widgets.slider("##fogstart", visuals.fogStart, 0f, 1f, "%.2f")
                    ?.let { visuals.fogStart = minOf(it, visuals.fogEnd) }
                Widgets.property("Fog end")
                Widgets.slider("##fogend", visuals.fogEnd, 0.05f, 2f, "%.2f")
                    ?.let { visuals.fogEnd = maxOf(it, visuals.fogStart) }
            }
            row("Override fog colour", visuals.overrideFogColor) { visuals.overrideFogColor = it }
            if (visuals.overrideFogColor) {
                Widgets.property("Fog colour")
                colorEdit("##fogcolor", visuals.fogColor)?.let { visuals.fogColor = it }
            }
            row("Override sky colour", visuals.overrideSkyColor) { visuals.overrideSkyColor = it }
            if (visuals.overrideSkyColor) {
                Widgets.property("Sky colour")
                colorEdit("##skycolor", visuals.skyColor)?.let { visuals.skyColor = it }
                Widgets.wrappedText("Disable Sky and set a solid colour for chroma keying.", EditorTheme.TEXT_DIM.u32)
            }
            Widgets.endProperties()
        }

        Widgets.header("Guides & overlays")
        if (Widgets.beginProperties("guides")) {
            row("Center guide", visuals.centerGuide) { visuals.centerGuide = it }
            row("Rule of thirds", visuals.thirdsGuide) { visuals.thirdsGuide = it }
            row("Real-time clock overlay", visuals.rtcOverlay) { visuals.rtcOverlay = it }
            Widgets.endProperties()
        }
    }

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
    }
}
