package gg.sona.afterimage.mc.common

import org.lwjgl.sdl.SDLEvents
import org.lwjgl.sdl.SDLKeyboard
import org.lwjgl.sdl.SDLMouse
import org.lwjgl.sdl.SDLProperties
import org.lwjgl.sdl.SDLVideo
import org.lwjgl.sdl.SDL_Event
import org.lwjgl.sdl.SDL_EventFilter
import org.lwjgl.system.MemoryStack
import java.nio.ByteBuffer

object SdlWindow {
    interface Display {
        val handle: Long
        val isCreated: Boolean
        val windowWidth: Int
        val windowHeight: Int
        val framebufferWidth: Int
        val framebufferHeight: Int
    }

    private object NoDisplay : Display {
        override val handle: Long get() = 0L
        override val isCreated: Boolean get() = false
        override val windowWidth: Int get() = 0
        override val windowHeight: Int get() = 0
        override val framebufferWidth: Int get() = 0
        override val framebufferHeight: Int get() = 0
    }

    @Volatile
    var display: Display = NoDisplay
    private var keyboardState: ByteBuffer? = null
    private var dropWatch: SDL_EventFilter? = null

    val handle: Long get() = display.handle
    val isCreated: Boolean get() = display.isCreated
    val windowWidth: Int get() = display.windowWidth
    val windowHeight: Int get() = display.windowHeight
    val framebufferWidth: Int get() = display.framebufferWidth
    val framebufferHeight: Int get() = display.framebufferHeight

    val displayScale: Float
        get() {
            if (!isCreated) return 1f
            val scale = SDLVideo.SDL_GetWindowDisplayScale(handle)
            return if (scale > 0f) scale else 1f
        }

    fun nativeHandle(): Long =
        SDLProperties.SDL_GetPointerProperty(
            SDLVideo.SDL_GetWindowProperties(handle),
            SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER,
            0L,
        )

    fun cursorPosition(out: DoubleArray) {
        MemoryStack.stackPush().use { stack ->
            val x = stack.mallocFloat(1)
            val y = stack.mallocFloat(1)
            SDLMouse.SDL_GetMouseState(x, y)
            out[0] = x[0].toDouble()
            out[1] = y[0].toDouble()
        }
    }

    fun relativeMotion(out: DoubleArray) {
        MemoryStack.stackPush().use { stack ->
            val x = stack.mallocFloat(1)
            val y = stack.mallocFloat(1)
            SDLMouse.SDL_GetRelativeMouseState(x, y)
            out[0] = x[0].toDouble()
            out[1] = y[0].toDouble()
        }
    }

    fun mouseButtons(): Int = SDLMouse.nSDL_GetMouseState(0L, 0L)

    fun keyDown(scancode: Int): Boolean {
        val cached: ByteBuffer? = keyboardState ?: SDLKeyboard.SDL_GetKeyboardState()
        val keys = cached ?: return false
        keyboardState = keys
        return scancode in 0 until keys.limit() && keys.get(scancode) != 0.toByte()
    }

    fun setRelativeMouseMode(enabled: Boolean) {
        if (isCreated) SDLMouse.SDL_SetWindowRelativeMouseMode(handle, enabled)
    }

    fun warpCursor(x: Double, y: Double) {
        if (isCreated) SDLMouse.SDL_WarpMouseInWindow(handle, x.toFloat(), y.toFloat())
    }

    fun watchFileDrops(listener: (List<String>) -> Unit) {
        dropWatch?.let { SDLEvents.SDL_RemoveEventWatch(it, 0L) }
        val pending = ArrayList<String>()
        val watch = SDL_EventFilter.create { _, address ->
            val event = SDL_Event.create(address)
            when (event.type()) {
                SDLEvents.SDL_EVENT_DROP_FILE -> {
                    val path: String? = event.drop().dataString()
                    if (path != null) pending += path
                }
                SDLEvents.SDL_EVENT_DROP_COMPLETE -> if (pending.isNotEmpty()) {
                    val dropped = ArrayList(pending)
                    pending.clear()
                    listener(dropped)
                }
            }
            true
        }
        SDLEvents.SDL_AddEventWatch(watch, 0L)
        dropWatch = watch
    }
}
