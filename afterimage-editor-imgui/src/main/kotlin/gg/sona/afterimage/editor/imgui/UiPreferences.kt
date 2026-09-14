package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.editor.LaneKind
import gg.sona.afterimage.editor.host.EditorHost

class UiPreferences(private val host: EditorHost) {

    private val cache = HashMap<String, Boolean>()
    private var laneOrderCache: List<LaneKind>? = null

    var viewportOverlay: Boolean
        get() = read("editor.viewportOverlay", true)
        set(value) = write("editor.viewportOverlay", value)

    var cameraPreview: Boolean
        get() = read("editor.cameraPreview", true)
        set(value) = write("editor.cameraPreview", value)


    var tipsOnStart: Boolean
        get() = read("editor.tipsOnStart", false)
        set(value) = write("editor.tipsOnStart", value)

    var autosave: Boolean
        get() = read("editor.autosave", true)
        set(value) = write("editor.autosave", value)

    var loopPlayback: Boolean
        get() = read("playback.loop", false)
        set(value) = write("playback.loop", value)

    var openFolderAfterExport: Boolean
        get() = read("export.openFolder", true)
        set(value) = write("export.openFolder", value)

    var showPathInWorld: Boolean
        get() = read("editor.showPath", true)
        set(value) = write("editor.showPath", value)

    var keyframeLabels: Boolean
        get() = read("editor.keyframeLabels", true)
        set(value) = write("editor.keyframeLabels", value)

    var entityBoxes: Boolean
        get() = read("editor.entityBoxes", true)
        set(value) = write("editor.entityBoxes", value)

    var sceneGizmos: Boolean
        get() = read("editor.sceneGizmos", true)
        set(value) = write("editor.sceneGizmos", value)

    var graphDocked: Boolean
        get() = read("editor.layout.graphDocked.v2", false)
        set(value) = write("editor.layout.graphDocked.v2", value)

    var searchDockedRight: Boolean
        get() = read("editor.layout.searchRight", false)
        set(value) = write("editor.layout.searchRight", value)

    var orientationGizmo: Boolean
        get() = read("editor.orientationGizmo", true)
        set(value) = write("editor.orientationGizmo", value)

    var lookPreview: Boolean
        get() = read("editor.lookPreview", true)
        set(value) = write("editor.lookPreview", value)

    var libraryList: Boolean
        get() = read("library.list", false)
        set(value) = write("library.list", value)

    var libraryThumbSize: Float
        get() = thumbSizeCache ?: (host.preference("library.thumbSize")?.toFloatOrNull() ?: 232f).also {
            thumbSizeCache = it
        }
        set(value) {
            thumbSizeCache = value
            host.setPreference("library.thumbSize", value.toString())
        }

    private var thumbSizeCache: Float? = null

    var laneOrder: List<LaneKind>
        get() = laneOrderCache ?: readLaneOrder().also { laneOrderCache = it }
        set(value) {
            laneOrderCache = value
            host.setPreference("editor.timeline.laneOrder", value.joinToString(",") { it.name })
        }

    private fun readLaneOrder(): List<LaneKind> {
        val saved = host.preference("editor.timeline.laneOrder")?.split(",")
            ?.mapNotNull { name -> LaneKind.entries.firstOrNull { it.name == name } } ?: emptyList()
        return saved + LaneKind.entries.filter { it !in saved }
    }

    private fun read(key: String, default: Boolean): Boolean =
        cache.getOrPut(key) { host.preference(key)?.toBooleanStrictOrNull() ?: default }

    private fun write(key: String, value: Boolean) {
        cache[key] = value
        host.setPreference(key, value.toString())
    }
}
