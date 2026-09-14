package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.editor.host.EditorHost
import imgui.ImDrawList

object Brand {

    const val NAME = "Afterimage"
    const val COPYRIGHT = "Copyright © 2026 Sona Interactive, Inc. All Rights Reserved"

    private const val LOGO = "/brand/logo.png"
    private const val WORDMARK = "/brand/wordmark.png"
    private const val MARK_U0 = 14f / 1923f
    private const val MARK_U1 = 425f / 1923f
    private const val MARK_V0 = 3f / 425f
    private const val MARK_V1 = 1f
    private const val MARK_ASPECT = 411f / 422f

    fun icon(host: EditorHost, list: ImDrawList, x: Float, y: Float, size: Float, rounding: Float): Boolean {
        val texture = host.imageTexture(LOGO) ?: return false
        list.addImageRounded(texture[0].toLong(), x, y, x + size, y + size, 0f, 0f, 1f, 1f, WHITE, rounding)
        return true
    }

    fun markWidth(height: Float): Float = height * MARK_ASPECT

    fun mark(host: EditorHost, list: ImDrawList, x: Float, y: Float, height: Float): Boolean {
        val texture = host.imageTexture(WORDMARK) ?: return false
        list.addImage(texture[0].toLong(), x, y, x + markWidth(height), y + height, MARK_U0, MARK_V0, MARK_U1, MARK_V1, WHITE)
        return true
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
}
