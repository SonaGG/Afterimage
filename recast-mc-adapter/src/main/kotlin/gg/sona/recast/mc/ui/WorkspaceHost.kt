package gg.sona.recast.mc.ui

import gg.sona.recast.editor.imgui.*
import imgui.ImGui
import imgui.flag.ImGuiConfigFlags
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.LogManager
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

class WorkspaceHost(private val minecraft: Minecraft, private val iniPath: Path) {
    private val logger = LogManager.getLogger("Recast")
    private val renderer = ImGuiRenderer()
    private var input: ImGuiInput? = null
    private var started = false
    private var failed = false
    private var frameFailures = 0
    private var lastFrameNanos = System.nanoTime()
    private var workspace: EditorWorkspace? = null
    private var exportView: ExportView? = null

    var consumesEscape: Boolean = false
        private set

    private var escapeGuardUntilNanos = 0L

    @Volatile
    var viewportHovered: Boolean = false
        private set

    @Volatile
    private var worldViewport: IntArray? = null

    val isOpen: Boolean get() = minecraft.screen is WorkspaceScreen

    fun bind(context: EditorContext) {
        workspace = EditorWorkspace(context)
        exportView = ExportView(context)
    }

    fun open() {
        if (failed || workspace == null) return
        if (minecraft.screen is WorkspaceScreen) return
        minecraft.openScreen(WorkspaceScreen(this))
    }

    fun close() {
        if (minecraft.screen is WorkspaceScreen) minecraft.openScreen(null)
    }

    fun toggle() = if (isOpen) close() else open()

    fun render() {
        if (failed) return
        if (!started) start()
        if (!started) return
        val input = input ?: return
        val editor = workspace ?: return
        try {
            applyPendingScale()
            if (!input.beginFrame()) return
            ImGui.newFrame()
            val now = System.nanoTime()
            lastFrameNanos = now
            val guardedBefore = ImGui.getIO().wantTextInput || editor.consumesEscape
            editor.draw(FrameContext(ImGui.getIO().displaySizeX, ImGui.getIO().displaySizeY, now))
            val io = ImGui.getIO()
            if (guardedBefore && ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape, false)) escapeGuardUntilNanos =
                now + ESCAPE_GUARD_NANOS
            consumesEscape = io.wantTextInput || editor.consumesEscape || now < escapeGuardUntilNanos
            textInputActive = io.wantTextInput
            viewportHovered = !io.wantCaptureMouse
            worldViewport = editor.frameRect?.let { rect ->
                val scaleX = input.framebufferSizeX / maxOf(1f, io.displaySizeX)
                val scaleY = input.framebufferSizeY / maxOf(1f, io.displaySizeY)
                intArrayOf(
                    (rect.x * scaleX).roundToInt(),
                    (input.framebufferSizeY - (rect.y + rect.height) * scaleY).roundToInt(),
                    (rect.width * scaleX).roundToInt(),
                    (rect.height * scaleY).roundToInt(),
                )
            }
            ImGui.render()
            renderer.render(ImGui.getDrawData(), input.framebufferSizeX, input.framebufferSizeY)
            frameFailures = 0
        } catch (error: Throwable) {
            frameFailures++
            if (frameFailures >= MAX_FRAME_FAILURES) {
                failed = true
                logger.error("Recast workspace crashed repeatedly and has been disabled for this session", error)
            } else {
                logger.error(
                    "Recast workspace frame failed (attempt {} of {}), skipping frame",
                    frameFailures,
                    MAX_FRAME_FAILURES,
                    error
                )
                runCatching { ImGui.endFrame() }
            }
        }
    }

    fun renderExport() {
        if (failed) return
        if (!started) start()
        if (!started) return
        val input = input ?: return
        val view = exportView ?: return
        try {
            applyPendingScale()
            if (!input.beginFrame()) return
            ImGui.newFrame()
            val now = System.nanoTime()
            lastFrameNanos = now
            view.draw(FrameContext(ImGui.getIO().displaySizeX, ImGui.getIO().displaySizeY, now))
            consumesEscape = false
            textInputActive = false
            viewportHovered = false
            ImGui.render()
            renderer.render(ImGui.getDrawData(), input.framebufferSizeX, input.framebufferSizeY)
            frameFailures = 0
        } catch (error: Throwable) {
            frameFailures++
            if (frameFailures >= MAX_FRAME_FAILURES) {
                failed = true
                logger.error("Recast export screen crashed repeatedly and has been disabled for this session", error)
            } else {
                logger.error(
                    "Recast export screen frame failed (attempt {} of {}), skipping frame",
                    frameFailures,
                    MAX_FRAME_FAILURES,
                    error
                )
                runCatching { ImGui.endFrame() }
            }
        }
    }

    var onViewportScroll: (Float) -> Unit = {}
    var onFilesDropped: (List<String>) -> Unit = {}
    var uiScale: Float = 1f
        private set
    private var pendingScale: Float? = null

    fun requestUiScale(scale: Float) {
        pendingScale = scale.coerceIn(0.75f, 2.5f)
    }

    private fun applyPendingScale() {
        val scale = pendingScale ?: return
        pendingScale = null
        if (scale == uiScale) return
        uiScale = scale
        EditorFonts.load(scale)
        renderer.createFontAtlas()
        EditorTheme.apply()
        ImGui.getStyle().scaleAllSizes(scale)
    }

    private fun detectScale(window: Long): Float {
        val override = System.getenv("RECAST_UI_SCALE")?.toFloatOrNull()
        if (override != null && override > 0f) return override
        val x = FloatArray(1)
        val y = FloatArray(1)
        GLFW.glfwGetWindowContentScale(window, x, y)
        return if (x[0] > 0f) x[0] else 1f
    }

    var textInputActive: Boolean = false
        private set

    fun lockCursor(x: Double, y: Double) {
        input?.lockCursor(x, y)
    }

    fun unlockCursor() {
        input?.unlockCursor()
    }

    fun scroll(amount: Float) {
        if (isOpen && viewportHovered) {
            onViewportScroll(amount)
            return
        }
        input?.scroll(amount)
    }

    fun typed(character: Char) {
        if (!started) return
        if (character.code >= 32 && character != Char.MAX_VALUE) ImGui.getIO().addInputCharacter(character.code)
    }

    fun onScreenClosed() {
        viewportHovered = false
        worldViewport = null
    }

    fun currentWorldViewport(): IntArray? = if (isOpen) worldViewport else null

    fun applyWorldViewport() {
        if (!isOpen) return
        val viewport = worldViewport ?: return
        if (viewport[2] <= 0 || viewport[3] <= 0) return
        org.lwjgl.opengl.GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
    }

    fun shutdown() {
        if (!started) return
        started = false
        runCatching { renderer.destroy() }
        runCatching { ImGui.destroyContext() }
    }

    private fun start() {
        if (started || failed) return
        try {
            val window = GLFW.glfwGetCurrentContext()
            require(window != 0L) { "no current GLFW context" }
            Files.createDirectories(iniPath.parent)
            ImGui.createContext()
            ImGui.setAssertCallback(object : imgui.assertion.ImAssertCallback() {
                override fun imAssertCallback(assertion: String, line: Int, file: String) {
                    throw IllegalStateException("ImGui assertion failed: $assertion ($file:$line)")
                }
            })
            val io = ImGui.getIO()
            io.addConfigFlags(ImGuiConfigFlags.DockingEnable)
            io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard)
            io.addBackendFlags(imgui.flag.ImGuiBackendFlags.RendererHasVtxOffset)
            io.iniFilename = iniPath.toString()
            uiScale = detectScale(window)
            EditorTheme.apply()
            ImGui.getStyle().scaleAllSizes(uiScale)
            EditorFonts.load(uiScale)
            renderer.start()
            renderer.createFontAtlas()
            input = ImGuiInput(window)
            GLFW.glfwSetDropCallback(window) { _, count, names ->
                val dropped = ArrayList<String>(count)
                for (index in 0 until count) dropped += org.lwjgl.glfw.GLFWDropCallback.getName(names, index)
                onFilesDropped(dropped)
            }
            started = true
            logger.info("Recast workspace initialised")
        } catch (error: Throwable) {
            failed = true
            logger.error("Recast could not initialise ImGui; the workspace is unavailable", error)
        }
    }

    private companion object {
        const val ESCAPE_GUARD_NANOS = 400_000_000L
        const val MAX_FRAME_FAILURES = 5
    }
}
