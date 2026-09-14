package gg.sona.recast.editor

enum class MomentKind(val label: String, val color: Int) {
    KILL("Kill", 0xFF453A),
    MULTI_KILL("Multi kill", 0xFF6B6B),
    CLUTCH("Clutch", 0xFFD60A),
    DEATH("Death", 0xBF5AF2),
    ESCAPE("Escape", 0x66D4CF),
    EXPLOSION("Explosion", 0xFF8A3D),
    VICTORY("Victory", 0x30D158),
    HIGHLIGHT("Highlight", 0xFF375F),
    CUSTOM("Moment", 0xFFFFFF);

    companion object {
        fun of(ordinal: Int): MomentKind = entries[ordinal.coerceIn(0, entries.size - 1)]
    }
}
