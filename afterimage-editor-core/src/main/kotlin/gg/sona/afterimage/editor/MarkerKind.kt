package gg.sona.afterimage.editor

enum class MarkerKind(val label: String, val color: Int) {
    NOTE("Note", 0xFFFFFF),
    MOMENT("Moment", 0xFFD60A),
    SHOT("Shot", 0x66D4CF),
    PLAYER("Player", 0x30D158),
    EVENT("Event", 0xFF453A),
    CAMERA("Camera", 0xBF5AF2);

    companion object {
        fun of(ordinal: Int): MarkerKind = entries[ordinal.coerceIn(0, entries.size - 1)]
    }
}
