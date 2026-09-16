package gg.sona.afterimage.mc.common.ui

import gg.sona.afterimage.editor.imgui.*
import imgui.ImGui
import imgui.flag.ImGuiConfigFlags
import gg.sona.afterimage.mc.common.SdlWindow
import gg.sona.afterimage.core.log.AfterimageLog
import gg.sona.afterimage.gfx.Gfx
import gg.sona.afterimage.gfx.Target
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt


class WorkspaceHost(gfx: Gfx, private val screens: WorkspaceScreens, private val iniPath: Path) {
    private val logger = AfterimageLog.logger("Afterimage")
    private val renderer = ImGuiDraw(gfx)
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

    val isOpen: Boolean get() = screens.isWorkspaceOpen
    var applyViewport: (IntArray) -> Unit = {}

    fun bind(context: EditorContext) {
        workspace = EditorWorkspace(context)
        exportView = ExportView(context)
    }

    fun open() {
        if (failed || workspace == null) return
        if (screens.isWorkspaceOpen) return
        screens.openWorkspace(this)
    }

    fun close() {
        if (screens.isWorkspaceOpen) screens.closeWorkspace()
    }

    fun toggle() = if (isOpen) close() else open()

    var rendering = false
        private set

    fun render() {
        if (failed || rendering) return
        if (!started) start()
        if (!started) return
        val input = input ?: return
        val editor = workspace ?: return
        rendering = true
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
            renderer.render(ImGui.getDrawData(), screens.mainTarget(), input.framebufferSizeX, input.framebufferSizeY)
            frameFailures = 0
        } catch (error: Throwable) {
            frameFailures++
            if (frameFailures >= MAX_FRAME_FAILURES) {
                failed = true
                logger.error("Afterimage workspace crashed repeatedly and has been disabled for this session", error)
            } else {
                logger.error("Afterimage workspace frame failed (attempt $frameFailures of $MAX_FRAME_FAILURES), skipping frame", error)
                runCatching { ImGui.endFrame() }
            }
        } finally {
            rendering = false
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
            renderer.render(ImGui.getDrawData(), screens.mainTarget(), input.framebufferSizeX, input.framebufferSizeY)
            frameFailures = 0
        } catch (error: Throwable) {
            frameFailures++
            if (frameFailures >= MAX_FRAME_FAILURES) {
                failed = true
                logger.error("Afterimage export screen crashed repeatedly and has been disabled for this session", error)
            } else {
                logger.error("Afterimage export screen frame failed (attempt $frameFailures of $MAX_FRAME_FAILURES), skipping frame", error)
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

    private fun detectScale(): Float {
        val override = System.getenv("AFTERIMAGE_UI_SCALE")?.toFloatOrNull()
        if (override != null && override > 0f) return override
        return SdlWindow.displayScale
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
        applyViewport(viewport)
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
            require(SdlWindow.isCreated) { "SDL window not created" }
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
            uiScale = detectScale()
            EditorTheme.apply()
            ImGui.getStyle().scaleAllSizes(uiScale)
            EditorFonts.load(uiScale)
            renderer.start()
            renderer.createFontAtlas()
            input = ImGuiInput()
            SdlWindow.watchFileDrops { dropped -> onFilesDropped(dropped) }
            started = true
            logger.info("Afterimage workspace initialised")
        } catch (error: Throwable) {
            failed = true
            logger.error("Afterimage could not initialise ImGui; the workspace is unavailable", error)
        }
    }

    private companion object {
        const val ESCAPE_GUARD_NANOS = 400_000_000L
        const val MAX_FRAME_FAILURES = 5
    }
}
