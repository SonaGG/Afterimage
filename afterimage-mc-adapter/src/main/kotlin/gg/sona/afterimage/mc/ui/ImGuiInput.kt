package gg.sona.afterimage.mc.ui

import gg.sona.afterimage.mc.SdlWindow
import imgui.ImGui
import imgui.flag.ImGuiKey
import org.lwjgl.sdl.SDLMouse
import org.lwjgl.sdl.SDLScancode

class ImGuiInput {

    private val cursor = DoubleArray(2)
    private var lastTimeNanos = 0L

    val framebufferSizeX: Int get() = SdlWindow.framebufferWidth

    val framebufferSizeY: Int get() = SdlWindow.framebufferHeight

    fun beginFrame(): Boolean {
        val io = ImGui.getIO()

        val windowWidth = SdlWindow.windowWidth
        val windowHeight = SdlWindow.windowHeight
        if (windowWidth <= 0 || windowHeight <= 0) return false

        io.setDisplaySize(windowWidth.toFloat(), windowHeight.toFloat())
        io.setDisplayFramebufferScale(
            framebufferSizeX.toFloat() / windowWidth,
            framebufferSizeY.toFloat() / windowHeight,
        )

        val now = System.nanoTime()
        io.deltaTime =
            if (lastTimeNanos > 0L) ((now - lastTimeNanos) / 1_000_000_000.0).toFloat().coerceAtLeast(1e-6f) else 1f / 60f
        lastTimeNanos = now

        SdlWindow.cursorPosition(cursor)
        val locked = lockedCursor
        if (locked != null) io.setMousePos(
            locked[0].toFloat(),
            locked[1].toFloat()
        ) else io.setMousePos(cursor[0].toFloat(), cursor[1].toFloat())

        val buttons = SdlWindow.mouseButtons()
        io.mouseDown = booleanArrayOf(
            buttons and SDLMouse.SDL_BUTTON_LMASK != 0,
            buttons and SDLMouse.SDL_BUTTON_RMASK != 0,
            buttons and SDLMouse.SDL_BUTTON_MMASK != 0,
            false,
            false,
        )

        val ctrl = down(SDLScancode.SDL_SCANCODE_LCTRL) || down(SDLScancode.SDL_SCANCODE_RCTRL)
        val shift = down(SDLScancode.SDL_SCANCODE_LSHIFT) || down(SDLScancode.SDL_SCANCODE_RSHIFT)
        val alt = down(SDLScancode.SDL_SCANCODE_LALT) || down(SDLScancode.SDL_SCANCODE_RALT)
        val superKey = down(SDLScancode.SDL_SCANCODE_LGUI) || down(SDLScancode.SDL_SCANCODE_RGUI)
        io.addKeyEvent(ImGuiKey.ModCtrl, ctrl)
        io.addKeyEvent(ImGuiKey.ModShift, shift)
        io.addKeyEvent(ImGuiKey.ModAlt, alt)
        io.addKeyEvent(ImGuiKey.ModSuper, superKey)
        io.keyCtrl = ctrl
        io.keyShift = shift
        io.keyAlt = alt
        io.keySuper = superKey

        for ((key, scancode) in NAVIGATION) {
            io.addKeyEvent(key, down(scancode))
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
        SdlWindow.setRelativeMouseMode(true)
    }

    fun unlockCursor() {
        val locked = lockedCursor ?: return
        lockedCursor = null
        SdlWindow.setRelativeMouseMode(false)
        SdlWindow.warpCursor(locked[0], locked[1])
    }

    private fun down(scancode: Int) = SdlWindow.keyDown(scancode)

    private companion object {
        val NAVIGATION: List<Pair<Int, Int>> = buildList {
            add(ImGuiKey.LeftArrow to SDLScancode.SDL_SCANCODE_LEFT)
            add(ImGuiKey.RightArrow to SDLScancode.SDL_SCANCODE_RIGHT)
            add(ImGuiKey.UpArrow to SDLScancode.SDL_SCANCODE_UP)
            add(ImGuiKey.DownArrow to SDLScancode.SDL_SCANCODE_DOWN)
            add(ImGuiKey.Space to SDLScancode.SDL_SCANCODE_SPACE)
            add(ImGuiKey.Enter to SDLScancode.SDL_SCANCODE_RETURN)
            add(ImGuiKey.KeypadEnter to SDLScancode.SDL_SCANCODE_KP_ENTER)
            add(ImGuiKey.Escape to SDLScancode.SDL_SCANCODE_ESCAPE)
            add(ImGuiKey.Delete to SDLScancode.SDL_SCANCODE_DELETE)
            add(ImGuiKey.Backspace to SDLScancode.SDL_SCANCODE_BACKSPACE)
            add(ImGuiKey.Tab to SDLScancode.SDL_SCANCODE_TAB)
            add(ImGuiKey.Home to SDLScancode.SDL_SCANCODE_HOME)
            add(ImGuiKey.End to SDLScancode.SDL_SCANCODE_END)
            add(ImGuiKey.PageUp to SDLScancode.SDL_SCANCODE_PAGEUP)
            add(ImGuiKey.PageDown to SDLScancode.SDL_SCANCODE_PAGEDOWN)
            add(ImGuiKey.Insert to SDLScancode.SDL_SCANCODE_INSERT)
            add(ImGuiKey.Comma to SDLScancode.SDL_SCANCODE_COMMA)
            add(ImGuiKey.Period to SDLScancode.SDL_SCANCODE_PERIOD)
            add(ImGuiKey.Minus to SDLScancode.SDL_SCANCODE_MINUS)
            add(ImGuiKey.Equal to SDLScancode.SDL_SCANCODE_EQUALS)
            add(ImGuiKey.LeftBracket to SDLScancode.SDL_SCANCODE_LEFTBRACKET)
            add(ImGuiKey.RightBracket to SDLScancode.SDL_SCANCODE_RIGHTBRACKET)
            add(ImGuiKey.Slash to SDLScancode.SDL_SCANCODE_SLASH)
            add(ImGuiKey.Backslash to SDLScancode.SDL_SCANCODE_BACKSLASH)
            add(ImGuiKey.Semicolon to SDLScancode.SDL_SCANCODE_SEMICOLON)
            add(ImGuiKey.Apostrophe to SDLScancode.SDL_SCANCODE_APOSTROPHE)
            add(ImGuiKey.GraveAccent to SDLScancode.SDL_SCANCODE_GRAVE)
            add(ImGuiKey.LeftCtrl to SDLScancode.SDL_SCANCODE_LCTRL)
            add(ImGuiKey.RightCtrl to SDLScancode.SDL_SCANCODE_RCTRL)
            add(ImGuiKey.LeftShift to SDLScancode.SDL_SCANCODE_LSHIFT)
            add(ImGuiKey.RightShift to SDLScancode.SDL_SCANCODE_RSHIFT)
            add(ImGuiKey.LeftAlt to SDLScancode.SDL_SCANCODE_LALT)
            add(ImGuiKey.RightAlt to SDLScancode.SDL_SCANCODE_RALT)
            for (offset in 0 until 26) add(ImGuiKey.A + offset to SDLScancode.SDL_SCANCODE_A + offset)
            add(ImGuiKey._0 to SDLScancode.SDL_SCANCODE_0)
            for (offset in 0 until 9) add(ImGuiKey._1 + offset to SDLScancode.SDL_SCANCODE_1 + offset)
            for (offset in 0 until 12) add(ImGuiKey.F1 + offset to SDLScancode.SDL_SCANCODE_F1 + offset)
        }
    }
}
