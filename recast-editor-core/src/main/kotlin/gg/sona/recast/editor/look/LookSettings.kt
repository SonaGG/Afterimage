package gg.sona.recast.editor.look

data class LookSettings(
    var depthOfField: Boolean = false,
    var focusTargetId: Int? = null,
    var focusDistance: Double = 8.0,
    var aperture: Double = 0.5,
    var focusRange: Double = 1.0,
    var exposure: Double = 0.0,
    var contrast: Double = 1.0,
    var saturation: Double = 1.0,
    var lut: String = "",
    var lutStrength: Double = 1.0,
    var vignette: Double = 0.0,
    var vignetteSoftness: Double = 0.5,
    var letterbox: Double = 0.0,
    var grain: Double = 0.0,
    var grainSize: Double = 1.0,
) {
    val grades: Boolean
        get() = exposure != 0.0 || contrast != 1.0 || saturation != 1.0 || lut.isNotEmpty()

    val overlays: Boolean get() = vignette > 0.0 || letterbox > 0.0 || grain > 0.0

    val active: Boolean get() = depthOfField || grades || overlays

    fun reset() {
        val defaults = LookSettings()
        depthOfField = defaults.depthOfField
        focusTargetId = defaults.focusTargetId
        focusDistance = defaults.focusDistance
        aperture = defaults.aperture
        focusRange = defaults.focusRange
        exposure = defaults.exposure
        contrast = defaults.contrast
        saturation = defaults.saturation
        lut = defaults.lut
        lutStrength = defaults.lutStrength
        vignette = defaults.vignette
        vignetteSoftness = defaults.vignetteSoftness
        letterbox = defaults.letterbox
        grain = defaults.grain
        grainSize = defaults.grainSize
    }

    fun assign(other: LookSettings) {
        depthOfField = other.depthOfField
        focusTargetId = other.focusTargetId
        focusDistance = other.focusDistance
        aperture = other.aperture
        focusRange = other.focusRange
        exposure = other.exposure
        contrast = other.contrast
        saturation = other.saturation
        lut = other.lut
        lutStrength = other.lutStrength
        vignette = other.vignette
        vignetteSoftness = other.vignetteSoftness
        letterbox = other.letterbox
        grain = other.grain
        grainSize = other.grainSize
    }

    companion object {
        val LETTERBOX_PRESETS = listOf(
            "Off" to 0.0,
            "1.85" to 1.85,
            "2.00" to 2.0,
            "2.35" to 2.35,
            "2.39" to 2.39,
            "2.76" to 2.76,
        )
    }
}
