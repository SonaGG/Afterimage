package gg.sona.recast.mc.ui

import imgui.ImGui
import imgui.flag.ImGuiKey
import org.lwjgl.glfw.GLFW

// TODO: port to sdl/lenis
class ImGuiInput(private val window: Long) {

    private val cursorX = DoubleArray(1)
    private val cursorY = DoubleArray(1)
    private val windowWidth = IntArray(1)
    private val windowHeight = IntArray(1)
    private val framebufferWidth = IntArray(1)
    private val framebufferHeight = IntArray(1)
    private var lastTimeSeconds = 0.0

    val framebufferSizeX: Int get() = framebufferWidth[0]

    val framebufferSizeY: Int get() = framebufferHeight[0]

    fun beginFrame(): Boolean {
        val io = ImGui.getIO()

        GLFW.glfwGetWindowSize(window, windowWidth, windowHeight)
        GLFW.glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight)
        if (windowWidth[0] <= 0 || windowHeight[0] <= 0) return false

        io.setDisplaySize(windowWidth[0].toFloat(), windowHeight[0].toFloat())
        io.setDisplayFramebufferScale(
            framebufferWidth[0].toFloat() / windowWidth[0],
            framebufferHeight[0].toFloat() / windowHeight[0],
        )

        val now = GLFW.glfwGetTime()
        io.deltaTime = if (lastTimeSeconds > 0.0) (now - lastTimeSeconds).toFloat() else 1f / 60f
        lastTimeSeconds = now

        GLFW.glfwGetCursorPos(window, cursorX, cursorY)
        val locked = lockedCursor
        if (locked != null) io.setMousePos(
            locked[0].toFloat(),
            locked[1].toFloat()
        ) else io.setMousePos(cursorX[0].toFloat(), cursorY[0].toFloat())

        io.mouseDown = booleanArrayOf(
            pressed(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            pressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT),
            pressed(GLFW.GLFW_MOUSE_BUTTON_MIDDLE),
            false,
            false,
        )

        val ctrl = down(GLFW.GLFW_KEY_LEFT_CONTROL) || down(GLFW.GLFW_KEY_RIGHT_CONTROL)
        val shift = down(GLFW.GLFW_KEY_LEFT_SHIFT) || down(GLFW.GLFW_KEY_RIGHT_SHIFT)
        val alt = down(GLFW.GLFW_KEY_LEFT_ALT) || down(GLFW.GLFW_KEY_RIGHT_ALT)
        val superKey = down(GLFW.GLFW_KEY_LEFT_SUPER) || down(GLFW.GLFW_KEY_RIGHT_SUPER)
        io.addKeyEvent(ImGuiKey.ModCtrl, ctrl)
        io.addKeyEvent(ImGuiKey.ModShift, shift)
        io.addKeyEvent(ImGuiKey.ModAlt, alt)
        io.addKeyEvent(ImGuiKey.ModSuper, superKey)
        io.keyCtrl = ctrl
        io.keyShift = shift
        io.keyAlt = alt
        io.keySuper = superKey

        for ((key, glfwKey) in NAVIGATION) {
            io.addKeyEvent(key, down(glfwKey))
        }

        return true
    }

    fun scroll(amount: Float) {
        ImGui.getIO().mouseWheel = amount
    }

    var lockedCursor: DoubleArray? = null
        private set

    fun lockCursor(x: Double, y: Double) {
        lockedCursor = doubleArrayOf(x, y)
        GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED)
    }

    fun unlockCursor() {
        val locked = lockedCursor ?: return
        lockedCursor = null
        GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL)
        GLFW.glfwSetCursorPos(window, locked[0], locked[1])
    }

    private fun pressed(button: Int) = GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS

    private fun down(key: Int) = GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS

    private companion object {
        val NAVIGATION: List<Pair<Int, Int>> = buildList {
            add(ImGuiKey.LeftArrow to GLFW.GLFW_KEY_LEFT)
            add(ImGuiKey.RightArrow to GLFW.GLFW_KEY_RIGHT)
            add(ImGuiKey.UpArrow to GLFW.GLFW_KEY_UP)
            add(ImGuiKey.DownArrow to GLFW.GLFW_KEY_DOWN)
            add(ImGuiKey.Space to GLFW.GLFW_KEY_SPACE)
            add(ImGuiKey.Enter to GLFW.GLFW_KEY_ENTER)
            add(ImGuiKey.KeypadEnter to GLFW.GLFW_KEY_KP_ENTER)
            add(ImGuiKey.Escape to GLFW.GLFW_KEY_ESCAPE)
            add(ImGuiKey.Delete to GLFW.GLFW_KEY_DELETE)
            add(ImGuiKey.Backspace to GLFW.GLFW_KEY_BACKSPACE)
            add(ImGuiKey.Tab to GLFW.GLFW_KEY_TAB)
            add(ImGuiKey.Home to GLFW.GLFW_KEY_HOME)
            add(ImGuiKey.End to GLFW.GLFW_KEY_END)
            add(ImGuiKey.PageUp to GLFW.GLFW_KEY_PAGE_UP)
            add(ImGuiKey.PageDown to GLFW.GLFW_KEY_PAGE_DOWN)
            add(ImGuiKey.Insert to GLFW.GLFW_KEY_INSERT)
            add(ImGuiKey.Comma to GLFW.GLFW_KEY_COMMA)
            add(ImGuiKey.Period to GLFW.GLFW_KEY_PERIOD)
            add(ImGuiKey.Minus to GLFW.GLFW_KEY_MINUS)
            add(ImGuiKey.Equal to GLFW.GLFW_KEY_EQUAL)
            add(ImGuiKey.LeftBracket to GLFW.GLFW_KEY_LEFT_BRACKET)
            add(ImGuiKey.RightBracket to GLFW.GLFW_KEY_RIGHT_BRACKET)
            add(ImGuiKey.Slash to GLFW.GLFW_KEY_SLASH)
            add(ImGuiKey.Backslash to GLFW.GLFW_KEY_BACKSLASH)
            add(ImGuiKey.Semicolon to GLFW.GLFW_KEY_SEMICOLON)
            add(ImGuiKey.Apostrophe to GLFW.GLFW_KEY_APOSTROPHE)
            add(ImGuiKey.GraveAccent to GLFW.GLFW_KEY_GRAVE_ACCENT)
            add(ImGuiKey.LeftCtrl to GLFW.GLFW_KEY_LEFT_CONTROL)
            add(ImGuiKey.RightCtrl to GLFW.GLFW_KEY_RIGHT_CONTROL)
            add(ImGuiKey.LeftShift to GLFW.GLFW_KEY_LEFT_SHIFT)
            add(ImGuiKey.RightShift to GLFW.GLFW_KEY_RIGHT_SHIFT)
            add(ImGuiKey.LeftAlt to GLFW.GLFW_KEY_LEFT_ALT)
            add(ImGuiKey.RightAlt to GLFW.GLFW_KEY_RIGHT_ALT)
            for (offset in 0 until 26) add(ImGuiKey.A + offset to GLFW.GLFW_KEY_A + offset)
            for (offset in 0 until 10) add(ImGuiKey._0 + offset to GLFW.GLFW_KEY_0 + offset)
            for (offset in 0 until 12) add(ImGuiKey.F1 + offset to GLFW.GLFW_KEY_F1 + offset)
        }
    }
}
