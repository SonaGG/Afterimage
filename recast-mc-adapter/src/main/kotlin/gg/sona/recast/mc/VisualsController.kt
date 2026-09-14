package gg.sona.recast.mc

import gg.sona.recast.editor.host.VisualSettings
import gg.sona.recast.editor.host.WeatherOverride
import gg.sona.recast.mc.mixin.GameGuiAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.render.platform.GlStateManager
import net.minecraft.entity.Entity
import net.minecraft.entity.ItemEntity
import net.minecraft.entity.living.player.PlayerEntity
import net.minecraft.world.World
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11

class VisualsController(private val minecraft: Minecraft, val settings: VisualSettings) {

    var active: () -> Boolean = { false }
    var exporting: () -> Boolean = { false }
    var alphaExport: () -> Boolean = { false }
    var timeOfDayProvider: () -> Double? = { null }

    var mobTypeProvider: (Int) -> Int? = { null }

    private val fogBuffer = BufferUtils.createFloatBuffer(4)
    private var baseGamma = Float.NaN
    private var gammaManaged = false

    fun onFrame() {
        if (!active()) {
            restoreGamma()
            return
        }
        val world = minecraft.world ?: return
        applyTime(world)
        when (settings.weather) {
            WeatherOverride.RECORDED -> Unit
            WeatherOverride.CLEAR -> {
                world.setRain(0f)
                world.setThunder(0f)
            }

            WeatherOverride.RAIN -> {
                world.setRain(1f)
                world.setThunder(0f)
            }

            WeatherOverride.THUNDER -> {
                world.setRain(1f)
                world.setThunder(1f)
            }
        }
        val dispatcher = minecraft.getEntityRenderDispatcher()
        if (dispatcher.shouldRenderShadow() != settings.renderShadows) dispatcher.setRenderShadow(settings.renderShadows)
        if (dispatcher.shouldRenderHitboxes() != settings.renderHitboxes) dispatcher.setRenderHitboxes(settings.renderHitboxes)
        applyGamma()
        val gui = minecraft.gui as GameGuiAccessor
        if (!settings.showTitles) gui.`recast$setTitleTime`(0)
        if (!settings.showActionBar) gui.`recast$setOverlayMessageCooldown`(0)
    }

    fun timeOverride(): Long? {
        if (!active()) return null
        val trackTime = timeOfDayProvider()
        if (trackTime != null) return trackTime.toLong().coerceIn(0L, 23999L)
        return if (settings.overrideTime) Math.floorMod(settings.timeOfDay, 24000L) else null
    }

    fun onTick() {
        if (!active()) return
        val world = minecraft.world ?: return
        applyTime(world)
    }

    private fun applyTime(world: World) {
        val override = timeOverride() ?: return
        if (world.getTimeOfDay() != override) world.setTimeOfDay(override)
        val darkness = world.calculateAmbientDarkness(1f)
        if (world.getAmbientDarkness() != darkness) world.setAmbientDarkness(darkness)
    }

    private fun applyGamma() {
        val options = minecraft.options
        val target = when {
            settings.nightVision -> NIGHT_VISION_GAMMA
            settings.brightnessBoost > 0f -> baseGammaValue() + settings.brightnessBoost * BOOST_SCALE
            else -> Float.NaN
        }
        if (target.isNaN()) {
            restoreGamma()
            return
        }
        if (!gammaManaged) {
            baseGamma = options.gamma
            gammaManaged = true
        }
        options.gamma = target
    }

    private fun baseGammaValue(): Float = if (gammaManaged) baseGamma else minecraft.options.gamma

    private fun restoreGamma() {
        if (!gammaManaged) return
        minecraft.options.gamma = baseGamma
        gammaManaged = false
    }

    fun shouldHide(entity: Entity): Boolean {
        if (!active()) return false
        if (settings.hiddenDuringExport.contains(entity.getNetworkId()) && exporting()) return true
        if (settings.hideOnlyDuringExport && !exporting()) return hiddenByCategory(entity) || hiddenByType(entity)
        if (settings.isHidden(entity.getNetworkId())) return true
        return hiddenByCategory(entity) || hiddenByType(entity)
    }

    private fun hiddenByCategory(entity: Entity): Boolean = when {
        entity is PlayerEntity -> !settings.renderPlayers
        entity is ItemEntity -> !settings.renderItemsOnGround
        else -> !settings.renderEntities
    }

    private fun hiddenByType(entity: Entity): Boolean {
        val type = mobTypeProvider(entity.getNetworkId()) ?: return false
        return settings.isEntityTypeHidden(type)
    }

    fun hideNametags(): Boolean = active() && !settings.renderNametags

    fun hideNametagFor(entity: Entity): Boolean = active() && settings.overrideFor(entity.getNetworkId()).hideNametag

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

    fun onFogSetup() {
        if (!active() || !settings.overrideFog) return
        val far = minecraft.options.viewDistance * 16f
        GL11.glFogi(GL11.GL_FOG_MODE, GL11.GL_LINEAR)
        GL11.glFogf(GL11.GL_FOG_START, far * settings.fogStart)
        GL11.glFogf(GL11.GL_FOG_END, far * settings.fogEnd)
    }

    fun onClearColor() {
        if (!active()) return
        if (settings.overrideFogColor) {
            val rgb = settings.fogColor
            val r = ((rgb shr 16) and 0xFF) / 255f
            val g = ((rgb shr 8) and 0xFF) / 255f
            val b = (rgb and 0xFF) / 255f
            fogBuffer.clear()
            fogBuffer.put(r).put(g).put(b).put(1f)
            fogBuffer.flip()
            GL11.glFogfv(GL11.GL_FOG_COLOR, fogBuffer)
            GlStateManager.clearColor(r, g, b, clearAlpha())
        }
        if (settings.overrideSkyColor && !settings.renderSky) {
            val rgb = settings.skyColor
            GlStateManager.clearColor(
                ((rgb shr 16) and 0xFF) / 255f,
                ((rgb shr 8) and 0xFF) / 255f,
                (rgb and 0xFF) / 255f,
                clearAlpha()
            )
        }
    }

    private fun clearAlpha(): Float = if (alphaExport()) 0f else 1f

    fun shutdown() = restoreGamma()

    private companion object {
        const val NIGHT_VISION_GAMMA = 6f
        const val BOOST_SCALE = 4f
    }
}
