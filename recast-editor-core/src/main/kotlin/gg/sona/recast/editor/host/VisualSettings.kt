package gg.sona.recast.editor.host

class VisualSettings {
    var showHotbar: Boolean = true
    var showStatusBars: Boolean = true
    var showExperience: Boolean = true
    var showChat: Boolean = true
    var showScoreboard: Boolean = true
    var showBossBar: Boolean = true
    var showActionBar: Boolean = true
    var showTitles: Boolean = true
    var showVignette: Boolean = true

    var renderPlayers: Boolean = true
    var renderEntities: Boolean = true
    var renderNametags: Boolean = true
    var renderParticles: Boolean = true
    var renderSky: Boolean = true
    var renderClouds: Boolean = true
    var renderWeather: Boolean = true
    var renderBlockOutline: Boolean = false
    var renderShadows: Boolean = true
    var renderHitboxes: Boolean = false
    var renderItemsOnGround: Boolean = true

    var overrideTime: Boolean = false
    var timeOfDay: Long = 6000L
    var weather: WeatherOverride = WeatherOverride.RECORDED
    var overrideFog: Boolean = false
    var fogStart: Float = 0.75f
    var fogEnd: Float = 1.0f
    var overrideFogColor: Boolean = false
    var fogColor: Int = 0x8FB6E0
    var overrideSkyColor: Boolean = false
    var skyColor: Int = 0x000000
    var nightVision: Boolean = false
    var brightnessBoost: Float = 0f

    var centerGuide: Boolean = false
    var thirdsGuide: Boolean = false
    var rtcOverlay: Boolean = false

    val hiddenEntities: MutableSet<Int> = HashSet()
    var hideOnlyDuringExport: Boolean = false

    val hiddenEntityTypes: MutableSet<Int> = HashSet()

    val hiddenParticleTypes: MutableSet<Int> = HashSet()

    val hiddenDuringExport: MutableSet<Int> = HashSet()

    val entityOverrides: MutableMap<Int, EntityOverride> = HashMap()

    fun isHidden(entityId: Int): Boolean = entityId in hiddenEntities

    fun isEntityTypeHidden(type: Int): Boolean = type in hiddenEntityTypes

    fun isParticleTypeHidden(type: Int): Boolean = type in hiddenParticleTypes

    fun overrideFor(entityId: Int): EntityOverride = entityOverrides[entityId] ?: EntityOverride.NONE

    fun setOverride(entityId: Int, override: EntityOverride) {
        if (override == EntityOverride.NONE) entityOverrides.remove(entityId) else entityOverrides[entityId] = override
    }

    fun reset() {
        showHotbar = true
        showStatusBars = true
        showExperience = true
        showChat = true
        showScoreboard = true
        showBossBar = true
        showActionBar = true
        showTitles = true
        showVignette = true
        renderPlayers = true
        renderEntities = true
        renderNametags = true
        renderParticles = true
        renderSky = true
        renderClouds = true
        renderWeather = true
        renderBlockOutline = false
        renderShadows = true
        renderHitboxes = false
        renderItemsOnGround = true
        overrideTime = false
        timeOfDay = 6000L
        weather = WeatherOverride.RECORDED
        overrideFog = false
        fogStart = 0.75f
        fogEnd = 1.0f
        overrideFogColor = false
        overrideSkyColor = false
        nightVision = false
        brightnessBoost = 0f
        hiddenEntities.clear()
        hideOnlyDuringExport = false
        hiddenEntityTypes.clear()
        hiddenParticleTypes.clear()
        hiddenDuringExport.clear()
        entityOverrides.clear()
    }
}
