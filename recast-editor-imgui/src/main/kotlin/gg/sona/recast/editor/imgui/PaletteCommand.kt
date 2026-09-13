package gg.sona.recast.editor.imgui

class PaletteCommand(
    val title: String,
    val group: String,
    val shortcut: String = "",
    val icon: Icon = Icon.COMMAND,
    val enabled: Boolean = true,
    val run: () -> Unit,
)
