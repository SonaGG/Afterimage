package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.editor.LaneKind
import gg.sona.afterimage.editor.host.EditorHost

class UiPreferences(private val host: EditorHost) {

    private val cache = HashMap<String, Boolean>()
    private val sets = HashMap<String, PersistedSet>()
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

    var showAllPlayers: Boolean
        get() = read("editor.timeline.allPlayers", false)
        set(value) = write("editor.timeline.allPlayers", value)

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

    val shownLanes: PersistedSet get() = set("editor.timeline.shownLanes")

    val hiddenWorldRows: PersistedSet get() = set("editor.timeline.hiddenWorldRows")

    val collapsedSections: PersistedSet get() = set("editor.inspector.collapsed")

    fun set(key: String): PersistedSet = sets.getOrPut(key) { PersistedSet(key) }

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

    inner class PersistedSet(private val key: String) : AbstractMutableSet<String>() {
        private val items: MutableSet<String> =
            host.preference(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet()
                ?: LinkedHashSet()

        override val size: Int get() = items.size

        override fun contains(element: String): Boolean = items.contains(element)

        override fun add(element: String): Boolean = items.add(element).also { if (it) save() }

        override fun remove(element: String): Boolean = items.remove(element).also { if (it) save() }

        override fun iterator(): MutableIterator<String> = object : MutableIterator<String> {
            private val inner = items.iterator()
            override fun hasNext(): Boolean = inner.hasNext()
            override fun next(): String = inner.next()
            override fun remove() {
                inner.remove()
                save()
            }
        }

        fun toggle(element: String, present: Boolean): Boolean = if (present) add(element) else remove(element)

        operator fun contains(element: Enum<*>): Boolean = contains(element.name)

        fun add(element: Enum<*>): Boolean = add(element.name)

        fun remove(element: Enum<*>): Boolean = remove(element.name)

        private fun save() = host.setPreference(key, items.joinToString(","))
    }
}
