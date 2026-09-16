package gg.sona.afterimage.mc263.replay

import gg.sona.afterimage.editor.host.VisualSettings
import gg.sona.afterimage.editor.host.WeatherOverride
import gg.sona.afterimage.mc263.mixin.ClientClockInstanceAccessor
import gg.sona.afterimage.mc263.mixin.HudAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.fog.FogData
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import org.joml.Vector4f

class VisualsController(private val minecraft: Minecraft, val settings: VisualSettings) {
    var active: () -> Boolean = { false }
    var exporting: () -> Boolean = { false }
    var alphaExport: () -> Boolean = { false }
    var timeOfDayProvider: () -> Double? = { null }
    var mobTypeProvider: (Int) -> Int? = { null }
    private var baseGamma = Double.NaN
    private var gammaManaged = false

    fun onFrame() {
        if (!active()) {
            restoreGamma()
            return
        }
        val level = minecraft.level ?: return
        applyTime(level)
        when (settings.weather) {
            WeatherOverride.RECORDED -> Unit
            WeatherOverride.CLEAR -> {
                level.setRainLevel(0f)
                level.setThunderLevel(0f)
            }

            WeatherOverride.RAIN -> {
                level.setRainLevel(1f)
                level.setThunderLevel(0f)
            }

            WeatherOverride.THUNDER -> {
                level.setRainLevel(1f)
                level.setThunderLevel(1f)
            }
        }
        val shadows = minecraft.options.entityShadows()
        if (shadows.get() != settings.renderShadows) shadows.set(settings.renderShadows)
        applyGamma()
        val hud = minecraft.gui.hud as HudAccessor
        if (!settings.showTitles) hud.afterimage_setTitleTime(0)
        if (!settings.showActionBar) hud.afterimage_setOverlayMessageTime(0)
    }

    fun timeOverride(): Long? {
        if (!active()) return null
        val trackTime = timeOfDayProvider()
        if (trackTime != null) return trackTime.toLong().coerceIn(0L, 23999L)
        return if (settings.overrideTime) Math.floorMod(settings.timeOfDay, 24000L) else null
    }

    fun onTick() {
        if (!active()) return
        val level = minecraft.level ?: return
        applyTime(level)
    }

    private fun applyTime(level: ClientLevel) {
        val override = timeOverride() ?: return
        val clock = level.dimensionType().defaultClock().orElse(null) ?: return
        val instance = level.clockManager().getInstance(clock) as ClientClockInstanceAccessor
        val current = instance.afterimage_totalTicks()
        val aligned = current - Math.floorMod(current, 24000L) + override
        if (current != aligned) instance.afterimage_setTotalTicks(aligned)
        level.updateSkyBrightness()
    }

    private fun applyGamma() {
        val gamma = minecraft.options.gamma()
        val target = when {
            settings.nightVision -> NIGHT_VISION_GAMMA
            settings.brightnessBoost > 0f -> baseGammaValue() + settings.brightnessBoost * BOOST_SCALE
            else -> Double.NaN
        }
        if (target.isNaN()) {
            restoreGamma()
            return
        }
        if (!gammaManaged) {
            baseGamma = gamma.get()
            gammaManaged = true
        }
        gamma.set(target)
    }

    private fun baseGammaValue(): Double = if (gammaManaged) baseGamma else minecraft.options.gamma().get()

    private fun restoreGamma() {
        if (!gammaManaged) return
        minecraft.options.gamma().set(baseGamma)
        gammaManaged = false
    }

    fun shouldHide(entity: Entity): Boolean {
        if (!active()) return false
        if (settings.hiddenDuringExport.contains(entity.id) && exporting()) return true
        if (settings.hideOnlyDuringExport && !exporting()) return hiddenByCategory(entity) || hiddenByType(entity)
        if (settings.isHidden(entity.id)) return true
        return hiddenByCategory(entity) || hiddenByType(entity)
    }

    private fun hiddenByCategory(entity: Entity): Boolean = when (entity) {
        is Player -> !settings.renderPlayers
        is ItemEntity -> !settings.renderItemsOnGround
        else -> !settings.renderEntities
    }

    private fun hiddenByType(entity: Entity): Boolean {
        val type = mobTypeProvider(entity.id) ?: return false
        return settings.isEntityTypeHidden(type)
    }

    fun hideNametags(): Boolean = active() && !settings.renderNametags
    fun hideNametagFor(entity: Entity): Boolean = active() && settings.overrideFor(entity.id).hideNametag
    fun hideParticles(): Boolean = active() && !settings.renderParticles
    fun hideParticleType(type: Int): Boolean = active() && settings.isParticleTypeHidden(type)
    fun hideSky(): Boolean = active() && (!settings.renderSky || alphaExport())
    fun hideClouds(): Boolean = active() && !settings.renderClouds
    fun hideWeather(): Boolean = active() && !settings.renderWeather
    fun hideHotbar(): Boolean = active() && !settings.showHotbar
    fun hideStatusBars(): Boolean = active() && !settings.showStatusBars
    fun hideExperience(): Boolean = active() && !settings.showExperience
    fun hideBossBar(): Boolean = active() && !settings.showBossBar
    fun hideScoreboard(): Boolean = active() && !settings.showScoreboard
    fun hideVignette(): Boolean = active() && (!settings.showVignette || alphaExport())
    fun hideChat(): Boolean = active() && !settings.showChat

    fun onFogSetup(fog: FogData) {
        if (!active() || !settings.overrideFog) return
        val far = minecraft.options.effectiveRenderDistance * 16f
        fog.environmentalStart = far * settings.fogStart
        fog.environmentalEnd = far * settings.fogEnd
        fog.renderDistanceStart = far * settings.fogStart
        fog.renderDistanceEnd = far * settings.fogEnd
    }

    fun clearColor(original: Vector4f): Vector4f? {
        if (!active()) return null
        val alpha = if (alphaExport()) 0f else original.w
        if (settings.overrideSkyColor && !settings.renderSky) return rgb(settings.skyColor, alpha)
        if (settings.overrideFogColor) return rgb(settings.fogColor, alpha)
        return if (alphaExport()) Vector4f(original.x, original.y, original.z, 0f) else null
    }

    private fun rgb(rgb: Int, alpha: Float): Vector4f = Vector4f(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f, alpha)

    fun shutdown() = restoreGamma()

    private companion object {
        const val NIGHT_VISION_GAMMA = 6.0
        const val BOOST_SCALE = 4.0
    }
}
