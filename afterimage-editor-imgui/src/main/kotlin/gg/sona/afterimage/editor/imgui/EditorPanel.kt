package gg.sona.afterimage.editor.imgui

import imgui.type.ImBoolean

interface EditorPanel {
    val title: String

    val open: ImBoolean

    val dockArea: DockArea

    val icon: Icon

    fun draw(frame: FrameContext)
}
