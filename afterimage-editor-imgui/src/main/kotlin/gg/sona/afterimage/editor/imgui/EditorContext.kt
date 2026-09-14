package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.clip.ClipStore
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.EditorSession
import gg.sona.afterimage.editor.Selection
import gg.sona.afterimage.editor.host.EditorHost
import gg.sona.afterimage.editor.host.VisualSettings
import gg.sona.afterimage.editor.pose.BodyPart
import gg.sona.afterimage.render.ExportBackend

class EditorContext(
    val host: EditorHost,
    val clips: ClipStore,
    val exports: ExportBackend?,
) {
    var session: EditorSession? = null
    val timeline = TimelineView()
    var statusLine: String = ""
    var statusUntilNanos: Long = 0L
    var fullscreenRequested: Boolean = false
    val ui = UiPreferences(host)

    class Toast(val text: String, val untilNanos: Long, val color: EditorTheme.Rgb)

    val toasts = ArrayList<Toast>()
    var openPanelRequest: String? = null
    var searchRequest: String? = null

    var tool: SceneTool = SceneTool.MOVE
    var localSpace: Boolean = true
    var inspect: InspectTarget? = null
    var hint: String = ""

    fun openPanel(title: String) {
        openPanelRequest = title
    }

    var loopPlayback: Boolean
        get() = ui.loopPlayback
        set(value) {
            ui.loopPlayback = value
        }
    var selectedEntityId: Int? = null

    var selectedBodyPart: BodyPart? = null

    val visuals: VisualSettings get() = host.visuals
    var clipboard: KeyframeClipboard? = null

    val replay get() = session?.replay

    fun status(text: String, nowNanos: Long = System.nanoTime()) {
        statusLine = text
        statusUntilNanos = nowNanos + Nanos.ofSeconds(4)
        toast(text, nowNanos = nowNanos)
    }

    fun toast(text: String, color: EditorTheme.Rgb = EditorTheme.ACCENT, nowNanos: Long = System.nanoTime()) {
        toasts.removeIf { it.text == text }
        toasts += Toast(text, nowNanos + Nanos.ofMillis(2600), color)
        while (toasts.size > 3) toasts.removeAt(0)
    }

    fun selectEntity(
        id: Int?,
        name: String = "",
        isPlayer: Boolean = false,
        isRecorder: Boolean = false,
        uuid: String? = null
    ) {
        if (id != selectedEntityId) selectedBodyPart = null
        selectedEntityId = id
        inspect = if (id == null) null else InspectTarget.Entity(id, name, isPlayer, isRecorder, uuid)
        if (id != null) session?.selection = Selection.NONE
    }
}
