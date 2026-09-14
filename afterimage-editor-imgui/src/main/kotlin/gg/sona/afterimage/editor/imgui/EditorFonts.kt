package gg.sona.afterimage.editor.imgui

import imgui.ImFont
import imgui.ImFontConfig
import imgui.ImGui

object EditorFonts {
    lateinit var body: ImFont
        private set

    lateinit var bodyMedium: ImFont
        private set

    lateinit var small: ImFont
        private set

    lateinit var smallMedium: ImFont
        private set

    lateinit var label: ImFont
        private set

    lateinit var heading: ImFont
        private set

    lateinit var title: ImFont
        private set

    lateinit var display: ImFont
        private set

    lateinit var timecode: ImFont
        private set

    lateinit var timecodeLarge: ImFont
        private set

    val loaded: Boolean get() = ::body.isInitialized

    var scale: Float = 1f
        private set

    private var iconFonts: List<Pair<Float, ImFont>> = emptyList()

    fun px(value: Float): Float = value * scale

    fun load(scale: Float = 1f) {
        this.scale = scale
        val atlas = ImGui.getIO().fonts
        atlas.clear()
        val regular = bytes("Inter-Regular.ttf")
        val medium = bytes("Inter-Medium.ttf")
        val semiBold = bytes("Inter-SemiBold.ttf")
        val icons = bytes("Lucide.ttf")
        body = add(regular, 13f * scale).also { mergeIcons(icons, 13f * scale) }
        bodyMedium = add(medium, 13f * scale).also { mergeIcons(icons, 13f * scale) }
        small = add(regular, 11.5f * scale).also { mergeIcons(icons, 11.5f * scale) }
        smallMedium = add(medium, 11.5f * scale).also { mergeIcons(icons, 11.5f * scale) }
        label = add(semiBold, 10.5f * scale)
        heading = add(semiBold, 15f * scale)
        title = add(semiBold, 22f * scale)
        display = add(semiBold, 28f * scale)
        timecode = add(medium, 15f * scale)
        timecodeLarge = add(semiBold, 24f * scale)
        iconFonts = ICON_SIZES.map { size -> size * scale to addIcons(icons, size * scale) }
        ImGui.getIO().fontDefault = body
    }

    fun icons(size: Float): ImFont = iconFonts.minByOrNull { (raster, _) -> kotlin.math.abs(raster - size) }!!.second

    inline fun <T> with(font: ImFont, block: () -> T): T {
        ImGui.pushFont(font)
        try {
            return block()
        } finally {
            ImGui.popFont()
        }
    }

    private val glyphRanges =
        shortArrayOf(0x0020, 0x00FF, 0x2000, 0x206F, 0x2190, 0x21FF, 0x2200, 0x22FF, 0x25A0, 0x25FF, 0)

    private val iconRanges: ShortArray by lazy {
        val points = Icon.entries.map { it.codepoint }.distinct().sorted()
        val ranges = ArrayList<Int>()
        for (point in points) {
            if (ranges.isNotEmpty() && ranges[ranges.size - 1] == point - 1) ranges[ranges.size - 1] = point
            else {
                ranges += point
                ranges += point
            }
        }
        ranges += 0
        ShortArray(ranges.size) { ranges[it].toShort() }
    }

    private fun add(data: ByteArray, size: Float): ImFont {
        val config = ImFontConfig()
        config.oversampleH = 3
        config.oversampleV = 2
        config.pixelSnapH = false
        config.rasterizerMultiply = 1.08f
        config.glyphRanges = glyphRanges
        val font = ImGui.getIO().fonts.addFontFromMemoryTTF(data, size, config)
        config.destroy()
        return font
    }

    private fun iconConfig(): ImFontConfig {
        val config = ImFontConfig()
        config.oversampleH = 2
        config.oversampleV = 2
        config.pixelSnapH = false
        config.glyphRanges = iconRanges
        return config
    }

    private fun addIcons(data: ByteArray, size: Float): ImFont {
        val config = iconConfig()
        val font = ImGui.getIO().fonts.addFontFromMemoryTTF(data, size, config)
        config.destroy()
        return font
    }

    private fun mergeIcons(data: ByteArray, textSize: Float) {
        val iconSize = textSize * INLINE_ICON_SCALE
        val config = iconConfig()
        config.mergeMode = true
        config.glyphMinAdvanceX = iconSize
        config.setGlyphOffset(0f, textSize * 0.50f - (textSize * 0.80f - iconSize / 2f))
        ImGui.getIO().fonts.addFontFromMemoryTTF(data, iconSize, config)
        config.destroy()
    }

    private fun bytes(name: String): ByteArray =
        EditorFonts::class.java.getResourceAsStream("/fonts/$name")?.use { it.readBytes() }
            ?: throw IllegalStateException("font $name is not bundled")

    private val ICON_SIZES = floatArrayOf(9f, 11f, 13f, 15f, 18f, 22f, 26f, 32f)

    const val INLINE_ICON_SCALE = 0.92f
}
