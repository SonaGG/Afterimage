package gg.sona.afterimage.editor.imgui


enum class SceneTool(val label: String, val icon: Icon, val key: String) {
    VIEW("View", Icon.TOOL_VIEW, "Q"),
    MOVE("Move", Icon.TOOL_MOVE, "W"),
    ROTATE("Rotate", Icon.TOOL_ROTATE, "E"),
    SCALE("Scale", Icon.TOOL_SCALE, "R"),
}
