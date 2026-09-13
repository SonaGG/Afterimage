package gg.sona.recast.editor.imgui

import imgui.ImGui

class PanelGuard(private val title: String) {
    private var lastFailure: String? = null

    inline fun run(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            report(error)
        }
    }

    fun report(error: Throwable) {
        val text = "${error.javaClass.simpleName}: ${error.message}"
        if (text != lastFailure) {
            lastFailure = text
            PanelLog.error("Panel $title failed", error)
        }
        ImGui.textColored(1f, 0.3f, 0.3f, 1f, "Panel failed: $text")
    }
}
