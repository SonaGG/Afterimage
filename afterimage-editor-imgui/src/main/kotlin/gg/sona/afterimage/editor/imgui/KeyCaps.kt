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

    fun width(text: String, font: ImFont = EditorFonts.smallMedium, padY: Float = PAD_Y): Float =
        maxOf(EditorFonts.with(font) { Widgets.textWidth(text) } + PAD_X * 2f, height(font, padY))

    fun height(font: ImFont = EditorFonts.smallMedium, padY: Float = PAD_Y): Float = font.fontSize + padY * 2f

    fun draw(
        list: ImDrawList,
        x: Float,
        y: Float,
        text: String,
        font: ImFont = EditorFonts.smallMedium,
        color: Int = EditorTheme.TEXT_MUTED.u32,
        padY: Float = PAD_Y,
    ): Float {
        val width = width(text, font, padY)
        val height = height(font, padY)
        val rounding = EditorFonts.px(4f)
        list.addRectFilled(x, y, x + width, y + height, EditorTheme.CONTROL.u32, rounding)
        list.addRect(x, y, x + width, y + height, EditorTheme.BORDER_SOFT.u32(0.14f), rounding)
        list.addLine(x + rounding, y + height - 1f, x + width - rounding, y + height - 1f, EditorTheme.BORDER.u32(0.5f), 1f)
        EditorFonts.with(font) { list.addText(x + (width - Widgets.textWidth(text)) / 2f, y + padY, color, text) }
        return width
    }

    fun piecesWidth(pieces: List<Piece>, font: ImFont = EditorFonts.smallMedium, padY: Float = PAD_Y): Float {
        var width = 0f
        for ((index, piece) in pieces.withIndex()) {
            if (index > 0) width += GAP
            width += if (piece.key) width(piece.text, font, padY) else Widgets.textWidth(piece.text)
        }
        return width
    }

    fun mixedWidth(text: String, font: ImFont = EditorFonts.smallMedium): Float = piecesWidth(pieces(text), font)

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
        val textSize = ImGui.getFontSize().toFloat()
        var x = x0
        for ((index, piece) in pieces.withIndex()) {
            if (index > 0) x += GAP
            if (piece.key) {
                x += draw(list, x, y0 + (lineHeight - height(font)) / 2f, piece.text, font, color)
            } else {
                list.addText(x, y0 + (lineHeight - textSize) / 2f, textColor, piece.text)
                x += Widgets.textWidth(piece.text)
            }
        }
        ImGui.dummy(maxOf(1f, x - x0), lineHeight)
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
        val capWidth = maxOf(width(text, font), EditorFonts.px(44f))
        val capHeight = height(font)
        val width = capWidth + EditorFonts.px(8f)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val pressed = ImGui.invisibleButton(id, width, height)
        val hovered = ImGui.isItemHovered()
        val list = ImGui.getWindowDrawList()
        val capX = x + (width - capWidth) / 2f
        val capY = y + (height - capHeight) / 2f
        val rounding = EditorFonts.px(4f)
        list.addRectFilled(
            capX,
            capY,
            capX + capWidth,
            capY + capHeight,
            if (ImGui.isItemActive()) EditorTheme.CONTROL_ACTIVE.u32 else if (hovered) EditorTheme.CONTROL_HOVER.u32 else EditorTheme.CONTROL.u32,
            rounding
        )
        list.addRect(capX, capY, capX + capWidth, capY + capHeight, EditorTheme.BORDER_SOFT.u32(0.14f), rounding)
        list.addLine(capX + rounding, capY + capHeight - 1f, capX + capWidth - rounding, capY + capHeight - 1f, EditorTheme.BORDER.u32(0.5f), 1f)
        EditorFonts.with(font) { list.addText(capX + (capWidth - Widgets.textWidth(text)) / 2f, capY + PAD_Y, color, text) }
        if (hovered) Widgets.cursorHand()
        return pressed
    }

    private val WHITESPACE = Regex("\\s+")
    private val PAD_X: Float get() = EditorFonts.px(5f)
    private val PAD_Y: Float get() = EditorFonts.px(2f)
    val GAP: Float get() = EditorFonts.px(5f)
}
