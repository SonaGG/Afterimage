package gg.sona.afterimage.editor.imgui

import imgui.ImDrawList
import imgui.ImFont
import imgui.ImGui

object KeyCaps {

    private val KEYS = setOf(
        "Ctrl", "Shift", "Alt", "Cmd", "Space", "Tab", "Esc", "Enter", "Home", "End", "Delete", "Backspace", "Insert",
        "Left", "Right", "Up", "Down", "Middle", "PgUp", "PgDn", "Del", "Wheel", "WASD", "Click", "Drag", "Double-click",
        ",", ".", "/", "-", "=", "[", "]", ";", "'",
    )
    private val MOUSE = setOf("click", "drag", "wheel")
    private val GESTURES = setOf("drag", "click")
    private val JOINERS = setOf("or", "/", "and")

    class Piece(val text: String, val key: Boolean)

    fun isCombo(token: String): Boolean {
        if (token.isEmpty()) return false
        val parts = token.split('+')
        if (parts.any { it.isEmpty() }) return false
        return parts.withIndex().all { (index, part) -> isKey(part) || (index > 0 && part in MOUSE) }
    }

    private fun isKey(part: String): Boolean {
        if (part in KEYS) return true
        if (part.length == 1 && part[0].isUpperCase()) return true
        return part.length in 2..3 && part[0] == 'F' && part.substring(1).all { it.isDigit() }
    }

    fun split(text: String): Pair<String, List<Piece>>? {
        val index = text.lastIndexOf("  ")
        if (index <= 0) return null
        val tokens = text.substring(index + 2).trim().split(WHITESPACE)
        if (tokens.none { isCombo(it) } || tokens.any { !isCombo(it) && it !in JOINERS }) return null
        return text.substring(0, index).trimEnd() to tokens.map { Piece(it, isCombo(it)) }
    }

    fun pieces(text: String): List<Piece> {
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        val result = ArrayList<Piece>(words.size)
        val run = StringBuilder()
        var index = 0
        while (index < words.size) {
            var word = words[index]
            if (isCombo(word)) {
                if (index + 1 < words.size && words[index + 1] in GESTURES) {
                    word += " " + words[index + 1]
                    index++
                }
                if (run.isNotEmpty()) {
                    result += Piece(run.toString(), false)
                    run.clear()
                }
                result += Piece(word, true)
            } else {
                if (run.isNotEmpty()) run.append(' ')
                run.append(word)
            }
            index++
        }
        if (run.isNotEmpty()) result += Piece(run.toString(), false)
        return result
    }

    fun hasKeys(text: String): Boolean = pieces(text).any { it.key }

    fun width(text: String, font: ImFont = EditorFonts.smallMedium, height: Float = height(font)): Float =
        maxOf(EditorFonts.with(font) { Widgets.textWidth(text) } + PAD_X * 2f, height)

    fun height(font: ImFont = EditorFonts.smallMedium): Float = font.fontSize + PAD_Y * 2f

    fun fits(rowHeight: Float, font: ImFont = EditorFonts.smallMedium): Float =
        (rowHeight - ROW_INSET * 2f).coerceIn(minOf(font.fontSize, rowHeight), height(font))

    private fun textTop(font: ImFont, height: Float): Float = height / 2f - (font.ascent - font.fontSize * CAP_CENTER)

    fun draw(
        list: ImDrawList,
        x: Float,
        y: Float,
        text: String,
        font: ImFont = EditorFonts.smallMedium,
        color: Int = EditorTheme.TEXT_MUTED.u32,
        height: Float = height(font),
        fill: Int = EditorTheme.CONTROL.u32,
    ): Float {
        val width = width(text, font, height)
        val rounding = minOf(EditorFonts.px(4f), height / 2f)
        list.addRectFilled(x, y + 1f, x + width, y + height + 1f, EditorTheme.BORDER.u32(0.45f), rounding)
        list.addRectFilled(x, y, x + width, y + height, fill, rounding)
        list.addRect(x, y, x + width, y + height, EditorTheme.BORDER_SOFT.u32(0.14f), rounding)
        EditorFonts.with(font) {
            list.addText(x + (width - Widgets.textWidth(text)) / 2f, y + textTop(font, height), color, text)
        }
        return width
    }

    fun piecesWidth(pieces: List<Piece>, font: ImFont = EditorFonts.smallMedium, height: Float = height(font)): Float {
        var width = 0f
        for ((index, piece) in pieces.withIndex()) {
            if (index > 0) width += GAP
            width += if (piece.key) width(piece.text, font, height) else Widgets.textWidth(piece.text)
        }
        return width
    }

    fun mixedWidth(text: String, font: ImFont = EditorFonts.smallMedium): Float = piecesWidth(pieces(text), font)

    fun drawPieces(
        list: ImDrawList,
        pieces: List<Piece>,
        x: Float,
        top: Float,
        rowHeight: Float,
        font: ImFont = EditorFonts.smallMedium,
        color: Int = EditorTheme.TEXT_MUTED.u32,
        textColor: Int = EditorTheme.TEXT_DIM.u32,
        capHeight: Float = height(font),
    ): Float {
        val textSize = ImGui.getFontSize().toFloat()
        var cursor = x
        for ((index, piece) in pieces.withIndex()) {
            if (index > 0) cursor += GAP
            if (piece.key) {
                cursor += draw(list, cursor, top + (rowHeight - capHeight) / 2f, piece.text, font, color, capHeight)
            } else {
                list.addText(cursor, top + (rowHeight - textSize) / 2f, textColor, piece.text)
                cursor += Widgets.textWidth(piece.text)
            }
        }
        return cursor - x
    }

    fun render(
        pieces: List<Piece>,
        font: ImFont = EditorFonts.smallMedium,
        color: Int = EditorTheme.TEXT_MUTED.u32,
        textColor: Int = EditorTheme.TEXT_DIM.u32,
        lineHeight: Float = ImGui.getTextLineHeight(),
    ) {
        val list = ImGui.getWindowDrawList()
        val x0 = ImGui.getCursorScreenPosX()
        val y0 = ImGui.getCursorScreenPosY()
        val width = drawPieces(list, pieces, x0, y0, lineHeight, font, color, textColor)
        ImGui.dummy(maxOf(1f, width), lineHeight)
    }

    fun cap(
        text: String,
        font: ImFont = EditorFonts.smallMedium,
        color: Int = EditorTheme.TEXT_MUTED.u32,
        lineHeight: Float = ImGui.getTextLineHeight(),
    ) = render(listOf(Piece(text, true)), font, color, color, lineHeight)

    fun mixed(
        text: String,
        font: ImFont = EditorFonts.smallMedium,
        color: Int = EditorTheme.TEXT_MUTED.u32,
        textColor: Int = EditorTheme.TEXT_DIM.u32,
        lineHeight: Float = ImGui.getTextLineHeight(),
    ) = render(pieces(text), font, color, textColor, lineHeight)

    fun button(
        id: String,
        text: String,
        color: Int = EditorTheme.TEXT.u32,
        font: ImFont = EditorFonts.smallMedium,
        height: Float = ImGui.getFrameHeight(),
    ): Boolean {
        val capHeight = height(font)
        val capWidth = maxOf(width(text, font, capHeight), EditorFonts.px(44f))
        val width = capWidth + EditorFonts.px(8f)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val pressed = ImGui.invisibleButton(id, width, height)
        val hovered = ImGui.isItemHovered()
        val fill = when {
            ImGui.isItemActive() -> EditorTheme.CONTROL_ACTIVE.u32
            hovered -> EditorTheme.CONTROL_HOVER.u32
            else -> EditorTheme.CONTROL.u32
        }
        draw(ImGui.getWindowDrawList(), x + (width - capWidth) / 2f, y + (height - capHeight) / 2f, text, font, color, capHeight, fill)
        if (hovered) Widgets.cursorHand()
        return pressed
    }

    private val WHITESPACE = Regex("\\s+")
    private const val CAP_CENTER = 0.30f
    private val PAD_X: Float get() = EditorFonts.px(5f)
    private val PAD_Y: Float get() = EditorFonts.px(2f)
    private val ROW_INSET: Float get() = EditorFonts.px(4.5f)
    val GAP: Float get() = EditorFonts.px(5f)
}
