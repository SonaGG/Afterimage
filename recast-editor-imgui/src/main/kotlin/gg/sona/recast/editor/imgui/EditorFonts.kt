package gg.sona.recast.editor.imgui

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

    lateinit var timecode: ImFont
        private set

    lateinit var timecodeLarge: ImFont
        private set

    val loaded: Boolean get() = ::body.isInitialized

    var scale: Float = 1f
        private set

    fun px(value: Float): Float = value * scale

    fun load(scale: Float = 1f) {
        this.scale = scale
        val atlas = ImGui.getIO().fonts
        atlas.clear()
        val regular = bytes("Inter-Regular.ttf")
        val medium = bytes("Inter-Medium.ttf")
        val semiBold = bytes("Inter-SemiBold.ttf")
        body = add(regular, 13f * scale)
        bodyMedium = add(medium, 13f * scale)
        small = add(regular, 11.5f * scale)
        smallMedium = add(medium, 11.5f * scale)
        label = add(semiBold, 10.5f * scale)
        heading = add(semiBold, 15f * scale)
        title = add(semiBold, 22f * scale)
        timecode = add(medium, 15f * scale)
        timecodeLarge = add(semiBold, 24f * scale)
        ImGui.getIO().fontDefault = body
    }

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

    private fun bytes(name: String): ByteArray =
        EditorFonts::class.java.getResourceAsStream("/fonts/$name")?.use { it.readBytes() }
            ?: throw IllegalStateException("font $name is not bundled")
}
