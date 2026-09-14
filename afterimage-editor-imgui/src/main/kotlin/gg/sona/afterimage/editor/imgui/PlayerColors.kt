package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.index.ReplayIndex

object PlayerColors {
    val PALETTE = listOf(
        EditorTheme.Rgb(0xFF9F0A), EditorTheme.Rgb(0x30D158), EditorTheme.Rgb(0xBF5AF2), EditorTheme.Rgb(0x66D4CF),
        EditorTheme.Rgb(0xFF375F), EditorTheme.Rgb(0xFFD60A), EditorTheme.Rgb(0xB7E36B), EditorTheme.Rgb(0xAC8E68),
    )

    fun of(index: ReplayIndex, name: String, isRecorder: Boolean): EditorTheme.Rgb {
        if (isRecorder) return EditorTheme.ACCENT_TEXT
        val others = index.playerNames.filter { candidate -> index.resolve(candidate).none { it.isRecorder } }
        val position = others.indexOf(name)
        return PALETTE[(if (position >= 0) position else name.hashCode() and 0x7fffffff) % PALETTE.size]
    }
}
