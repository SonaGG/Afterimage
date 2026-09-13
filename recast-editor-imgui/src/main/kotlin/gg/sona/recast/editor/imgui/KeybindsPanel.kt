package gg.sona.recast.editor.imgui

import imgui.ImGui
import imgui.flag.ImGuiKey

class KeybindsPanel(private val context: EditorContext) : DialogPanel("Keybinds", Icon.COMMAND, 480f, 460f) {

    private var listeningFor: String? = null

    override fun content(frame: FrameContext) {
        val bindings = context.host.keybindings()
        val conflicts = bindings.groupBy { it.keyCode }.filterValues { it.size > 1 }.keys
        if (listeningFor != null && ImGui.isKeyPressed(ImGuiKey.Escape, false)) listeningFor = null
        Widgets.wrappedText("Click a key to rebind it, then press any key. Escape cancels.", EditorTheme.TEXT_DIM.u32)
        ImGui.dummy(0f, EditorFonts.px(4f))
        if (Widgets.beginProperties("keybinds")) {
            for ((name, label, keyCode, keyName, isDefault) in bindings) {
                Widgets.property(label)
                val listening = listeningFor == name
                val conflicted = keyCode in conflicts
                val text = if (listening) "Press a key..." else keyName
                val color = when {
                    listening -> EditorTheme.ACCENT_TEXT.u32
                    conflicted -> EditorTheme.WARNING.u32
                    else -> EditorTheme.TEXT.u32
                }
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, color)
                if (Widgets.smallButton(text)) listeningFor = if (listening) null else name
                ImGui.popStyleColor()
                if (conflicted && !listening) Widgets.tooltip("Also bound to another action")
                if (!isDefault) {
                    ImGui.sameLine()
                    if (Widgets.smallButton("Reset")) {
                        context.host.resetKeybinding(name)
                        if (listening) listeningFor = null
                    }
                }
            }
            Widgets.endProperties()
        }
        listeningFor?.let { name ->
            context.host.captureNextKeyDown()?.let { code ->
                context.host.setKeybinding(name, code)
                listeningFor = null
            }
        }
    }
}
