package gg.sona.recast.editor.imgui

import gg.sona.recast.camera.CameraMode
import gg.sona.recast.camera.CameraPose
import gg.sona.recast.editor.*
import gg.sona.recast.editor.commands.*
import gg.sona.recast.protocol.MetadataEntry
import gg.sona.recast.replay.state.shadow.EntityKind
import gg.sona.recast.replay.state.shadow.ShadowClient
import gg.sona.recast.replay.state.shadow.ShadowEntity
import imgui.ImGui
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImString

class HierarchyPanel(private val context: EditorContext) :
    AbstractPanel("Hierarchy", DockArea.LEFT, Icon.LIST, flags = ImGuiWindowFlags.NoScrollbar) {

    private class Row(
        val id: Int,
        val name: String,
        val kind: String,
        val isPlayer: Boolean,
        val isRecorder: Boolean,
        val health: String,
        val distance: Double,
        val x: Double,
        val y: Double,
        val z: Double,
        val uuid: String?
    )

    private val filter = ImString("", 64)
    private val momentRename = ImString("", 64)
    private val expanded = HashSet<String>().apply { addAll(listOf("path", "players", "entities", "clips", "markers")) }
    private var rows: List<Row> = emptyList()
    private var lastRefreshNanos = 0L
    private var contextKeyframe: Long? = null

    override fun content(frame: FrameContext) {
        val session = context.session
        val replay = session?.replay
        if (session == null || replay == null) {
            Widgets.emptyState("No replay open", "Open one from the library", Icon.LIST)
            return
        }
        Widgets.search("##hierarchy-search", filter, "Filter")
        ImGui.dummy(0f, EditorFonts.px(2f))
        if (frame.nowNanos - lastRefreshNanos > REFRESH_NANOS || rows.isEmpty()) {
            lastRefreshNanos = frame.nowNanos
            rows = collect(replay.shadow, context.host.camera.currentPose())
        }
        val query = filter.get().trim().lowercase()
        ImGui.beginChild("##hierarchy-tree", 0f, 0f, false, ImGuiWindowFlags.None)
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.ItemSpacing, 0f, EditorFonts.px(1f))
        try {
            project(session, query)
            cameraPath(session, query)
            tracks(session, query)
            clips(session, query)
            markers(session, query)
            moments(session, query)
            people(session, query)
        } finally {
            ImGui.popStyleVar()
        }
        ImGui.endChild()
    }

    private fun project(session: EditorSession, query: String) {
        val name = session.project.name
        if (query.isNotEmpty() && !name.lowercase().contains(query)) return
        val selected = context.inspect == InspectTarget.Project && session.selection.isEmpty
        if (node(
                "project",
                0,
                Icon.FOLDER,
                name,
                EditorTheme.TEXT.u32,
                selected,
                false,
                if (session.project.dirty) "edited" else ""
            )
        ) {
            session.selection = Selection.NONE
            context.selectedEntityId = null
            context.inspect = InspectTarget.Project
        }
    }

    private fun cameraPath(session: EditorSession, query: String) {
        val keyframes = session.project.camera.keyframes()
        val lane = session.project.lane(LaneKind.CAMERA)
        val matching = if (query.isEmpty()) keyframes else keyframes.filter {
            TimeFormat.clock(it.timeNanos).contains(query) || "keyframe".contains(query)
        }
        if (query.isNotEmpty() && matching.isEmpty() && !"camera path".contains(query)) return
        val open = "path" in expanded || query.isNotEmpty()
        val laneSelected = context.inspect == InspectTarget.Lane(LaneKind.CAMERA) && session.selection.isEmpty
        val clicked = node(
            "path",
            0,
            Icon.PATH,
            "Camera Path",
            if (lane.muted) EditorTheme.TEXT_DIM.u32 else EditorTheme.TEXT.u32,
            laneSelected,
            keyframes.isNotEmpty(),
            "${keyframes.size}",
            open,
            eye = !lane.muted
        ) { visible ->
            session.execute(SetLaneState(LaneKind.CAMERA, lane.copy(muted = !visible)))
        }
        if (clicked) {
            session.selection = Selection.NONE
            context.selectedEntityId = null
            context.inspect = InspectTarget.Lane(LaneKind.CAMERA)
        }
        if (!open) return
        val playhead = session.playheadNanos
        for (keyframe in matching) {
            val selected = keyframe.timeNanos in session.selection.keyframeTimes
            val atPlayhead = Math.abs(keyframe.timeNanos - playhead) < 5_000_000L
            ImGui.pushID(keyframe.timeNanos.toString())
            try {
                val clickedRow = node(
                    "kf",
                    1,
                    Icon.KEYFRAME,
                    TimeFormat.clock(keyframe.timeNanos),
                    EditorTheme.modeColor(keyframe.mode).u32,
                    selected,
                    false,
                    if (atPlayhead) "playhead" else keyframe.mode.label.lowercase(),
                    iconColor = EditorTheme.modeColor(keyframe.mode).u32
                )
                if (clickedRow) {
                    val io = ImGui.getIO()
                    session.selection = when {
                        io.keyCtrl || io.keyShift -> if (selected) session.selection.withoutKeyframe(keyframe.timeNanos) else session.selection.withKeyframe(
                            keyframe.timeNanos,
                            true
                        )

                        else -> Selection(keyframeTimes = setOf(keyframe.timeNanos))
                    }
                    context.selectedEntityId = null
                    if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) session.replay?.seek(keyframe.timeNanos)
                }
                if (ImGui.isItemClicked(ImGuiMouseButton.Right)) {
                    contextKeyframe = keyframe.timeNanos
                    if (!selected) session.selection = Selection(keyframeTimes = setOf(keyframe.timeNanos))
                    ImGui.openPopup("kf-menu")
                }
                if (ImGui.beginPopup("kf-menu")) {
                    val targets = session.selection.keyframeTimes.ifEmpty { setOf(keyframe.timeNanos) }
                    if (ImGui.menuItem("Go to keyframe")) session.replay?.seek(keyframe.timeNanos)
                    if (ImGui.menuItem("Frame in scene", "F")) context.host.camera.frame(
                        keyframe.pose.position.x,
                        keyframe.pose.position.y,
                        keyframe.pose.position.z,
                        1.5
                    )
                    ImGui.separator()
                    if (ImGui.menuItem(if (targets.size > 1) "Delete ${targets.size} keyframes" else "Delete keyframe")) {
                        session.execute(RemoveKeyframes(targets))
                        session.selection = Selection.NONE
                    }
                    ImGui.endPopup()
                }
            } finally {
                ImGui.popID()
            }
        }
    }

    private fun tracks(session: EditorSession, query: String) {
        val lanes = ArrayList<Triple<LaneKind, String, Int>>()
        for (lane in ValueLane.entries) lanes += Triple(
            lane.kind,
            lane.label,
            session.project.valueTrack(lane).keyframes.size
        )
        lanes += Triple(LaneKind.VIEW, "View", session.project.views.keyframes.size)
        lanes += Triple(LaneKind.TEXTURE_PACK, "Texture pack", session.project.packs.keyframes.size)
        lanes += Triple(LaneKind.POSE, "Poses", session.project.poses.values.sumOf { it.keyframes.size })
        val visible = lanes.filter { (kind, label, count) -> count > 0 || kind in context.timeline.shownLanes }
            .filter { query.isEmpty() || it.second.lowercase().contains(query) }
        if (visible.isEmpty()) return
        val open = "tracks" in expanded || query.isNotEmpty()
        if (node(
                "tracks",
                0,
                Icon.SLIDERS,
                "Tracks",
                EditorTheme.TEXT.u32,
                false,
                true,
                "${visible.size}",
                open
            )
        ) toggle("tracks")
        if (!open) return
        for ((kind, label, count) in visible) {
            val state = session.project.lane(kind)
            val selected = context.inspect == InspectTarget.Lane(kind) && session.selection.isEmpty
            ImGui.pushID(kind.name)
            try {
                val clicked = node(
                    "lane",
                    1,
                    LANE_ICONS[kind] ?: Icon.SLIDERS,
                    label,
                    if (state.muted) EditorTheme.TEXT_DIM.u32 else EditorTheme.TEXT.u32,
                    selected,
                    false,
                    "$count",
                    eye = !state.muted
                ) { visibleNow ->
                    session.execute(SetLaneState(kind, state.copy(muted = !visibleNow)))
                }
                if (clicked) {
                    session.selection = Selection.NONE
                    context.selectedEntityId = null
                    context.inspect = InspectTarget.Lane(kind)
                    context.timeline.shownLanes.add(kind)
                }
            } finally {
                ImGui.popID()
            }
        }
    }

    private fun clips(session: EditorSession, query: String) {
        val clips = session.project.clips.sortedBy { it.startNanos }
        val matching = clips.filter { query.isEmpty() || it.title.lowercase().contains(query) }
        if (query.isNotEmpty() && matching.isEmpty()) return
        val open = "clips" in expanded || query.isNotEmpty()
        if (node(
                "clips",
                0,
                Icon.FILM,
                "Clips",
                EditorTheme.TEXT.u32,
                false,
                clips.isNotEmpty(),
                "${clips.size}",
                open
            )
        ) toggle("clips")
        if (!open) return
        for (clip in matching) {
            val selected = clip.id in session.selection.clipIds
            ImGui.pushID(clip.id.toString())
            try {
                if (node(
                        "clip",
                        1,
                        Icon.FILM,
                        clip.title,
                        EditorTheme.TEXT.u32,
                        selected,
                        false,
                        TimeFormat.short(clip.durationNanos),
                        iconColor = EditorTheme.CLIP_SELECTED.u32
                    )
                ) {
                    session.selection = session.selection.withClip(clip.id, ImGui.getIO().keyCtrl)
                    context.selectedEntityId = null
                    if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) ClipActions.play(session, clip)
                }
                if (ImGui.beginPopupContextItem("clip-menu")) {
                    if (ImGui.menuItem("Play")) ClipActions.play(session, clip)
                    if (ImGui.menuItem("Go to start")) session.replay?.seek(clip.startNanos)
                    ImGui.separator()
                    if (ImGui.menuItem("Delete")) {
                        session.execute(RemoveClip(clip.id))
                        context.clips.delete(clip.id)
                        session.selection = Selection.NONE
                    }
                    ImGui.endPopup()
                }
            } finally {
                ImGui.popID()
            }
        }
    }

    private fun markers(session: EditorSession, query: String) {
        val markers = session.project.markers.sortedBy { it.nanos }
        val matching = markers.filter { query.isEmpty() || it.label.lowercase().contains(query) }
        if (query.isNotEmpty() && matching.isEmpty()) return
        val open = "markers" in expanded || query.isNotEmpty()
        if (node(
                "markers",
                0,
                Icon.MARKER,
                "Markers",
                EditorTheme.TEXT.u32,
                false,
                markers.isNotEmpty(),
                "${markers.size}",
                open
            )
        ) toggle("markers")
        if (!open) return
        for (marker in matching) {
            val selected = marker.id in session.selection.markerIds
            ImGui.pushID(marker.id.toString())
            try {
                if (node(
                        "marker",
                        1,
                        MARKER_ICONS[marker.kind] ?: Icon.MARKER,
                        marker.label,
                        EditorTheme.TEXT.u32,
                        selected,
                        false,
                        TimeFormat.short(marker.nanos),
                        iconColor = Widgets.rgbToU32(marker.color)
                    )
                ) {
                    session.selection = session.selection.withMarker(marker.id, ImGui.getIO().keyCtrl)
                    context.selectedEntityId = null
                    if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) session.replay?.seek(marker.nanos)
                }
                if (ImGui.beginPopupContextItem("marker-menu")) {
                    if (ImGui.menuItem("Go to marker")) session.replay?.seek(marker.nanos)
                    if (ImGui.beginMenu("Type")) {
                        for (kind in MarkerKind.entries) if (ImGui.menuItem(
                                kind.label,
                                "",
                                marker.kind == kind
                            )
                        ) session.execute(
                            ReplaceMarker(
                                marker.id,
                                marker.copy(kind = kind, color = if (marker.kind == kind) marker.color else kind.color)
                            )
                        )
                        ImGui.endMenu()
                    }
                    ImGui.separator()
                    if (ImGui.menuItem("Delete")) {
                        session.execute(RemoveMarker(marker.id))
                        session.selection = Selection.NONE
                    }
                    ImGui.endPopup()
                }
            } finally {
                ImGui.popID()
            }
        }
    }

    private fun people(session: EditorSession, query: String) {
        val players = rows.filter { it.isPlayer }
            .sortedWith(compareByDescending<Row> { it.isRecorder }.thenBy { it.name.lowercase() })
        val others = rows.filter { !it.isPlayer }.sortedBy { it.distance }
        val matchingPlayers = players.filter { query.isEmpty() || it.name.lowercase().contains(query) }
        val matchingOthers =
            others.filter { query.isEmpty() || it.name.lowercase().contains(query) || it.kind.contains(query) }
        if (matchingPlayers.isNotEmpty() || query.isEmpty()) {
            val open = "players" in expanded || query.isNotEmpty()
            if (node(
                    "players",
                    0,
                    Icon.PERSON,
                    "Players",
                    EditorTheme.TEXT.u32,
                    false,
                    players.isNotEmpty(),
                    "${players.size}",
                    open
                )
            ) toggle("players")
            if (open) for (row in matchingPlayers) entityRow(
                session,
                row,
                if (row.isRecorder) Icon.USER else Icon.PERSON
            )
        }
        if (matchingOthers.isNotEmpty() || query.isEmpty()) {
            val open = "entities" in expanded || query.isNotEmpty()
            if (node(
                    "entities",
                    0,
                    Icon.CUBE,
                    "Entities",
                    EditorTheme.TEXT.u32,
                    false,
                    others.isNotEmpty(),
                    "${others.size}",
                    open
                )
            ) toggle("entities")
            if (open) for (row in matchingOthers.take(MAX_ENTITIES)) entityRow(session, row, Icon.CUBE)
            if (open && matchingOthers.size > MAX_ENTITIES) {
                ImGui.setCursorPosX(ImGui.getCursorPosX() + EditorFonts.px(34f))
                Widgets.smallText(
                    "${matchingOthers.size - MAX_ENTITIES} more, filter to find them",
                    EditorTheme.TEXT_DIM.u32
                )
            }
        }
    }

    private fun moments(session: EditorSession, query: String) {
        val all = MomentActions.all(session)
        if (all.isEmpty()) return
        val matching = all.filter {
            query.isEmpty() || it.label.lowercase().contains(query) || it.kind.label.lowercase().contains(query)
        }
        if (query.isNotEmpty() && matching.isEmpty()) return
        val open = "moments" in expanded || query.isNotEmpty()
        val kept = session.project.moments.size
        if (node(
                "moments",
                0,
                Icon.BOOKMARK,
                "Moments",
                EditorTheme.TEXT.u32,
                false,
                true,
                if (all.size > kept) "$kept + ${all.size - kept}" else "$kept",
                open
            )
        ) toggle("moments")
        if (!open) return
        for (moment in matching) {
            val selected = moment.id in session.selection.momentIds
            val isKept = MomentActions.isKept(session, moment)
            ImGui.pushID(moment.id.toString())
            try {
                if (node(
                        "moment",
                        1,
                        Icon.BOOKMARK,
                        moment.label,
                        if (isKept) EditorTheme.TEXT.u32 else EditorTheme.TEXT_MUTED.u32,
                        selected,
                        false,
                        TimeFormat.short(moment.peakNanos),
                        iconColor = Widgets.rgbToU32(moment.kind.color, if (isKept) 1f else 0.55f)
                    )
                ) {
                    session.selection = session.selection.withMoment(moment.id, ImGui.getIO().keyCtrl)
                    context.selectedEntityId = null
                    if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) MomentActions.play(session, moment)
                    else session.replay?.seek(moment.peakNanos)
                }
                if (ImGui.isItemClicked(ImGuiMouseButton.Right)) momentRename.set(moment.label)
                if (ImGui.beginPopupContextItem("moment-menu")) {
                    MomentActions.menu(context, session, moment, momentRename)
                    ImGui.endPopup()
                }
            } finally {
                ImGui.popID()
            }
        }
    }

    private fun entityRow(session: EditorSession, row: Row, icon: Icon) {
        val selected = context.selectedEntityId == row.id
        val hidden = context.visuals.isHidden(row.id)
        val settings = context.host.camera.settings
        val targeted = if (row.isRecorder) settings.targetsRecorder() else settings.targetEntityId == row.id
        val trailing = when {
            targeted && settings.mode != CameraMode.FREE -> settings.mode.label.lowercase()
            row.distance < 1000 -> String.format("%.0f m", row.distance)
            else -> "far"
        }
        ImGui.pushID(row.id)
        try {
            val clicked = node(
                "entity",
                1,
                icon,
                row.name,
                if (hidden) EditorTheme.TEXT_DIM.u32 else EditorTheme.TEXT.u32,
                selected,
                false,
                trailing,
                iconColor = if (row.isRecorder) EditorTheme.ACCENT_TEXT.u32 else if (hidden) EditorTheme.TEXT_DIM.u32 else EditorTheme.TEXT_MUTED.u32,
                eye = if (row.isRecorder) null else !hidden
            ) { visible ->
                val set = context.visuals.hiddenEntities
                if (visible) set.remove(row.id) else set.add(row.id)
            }
            if (clicked) {
                context.selectEntity(row.id, row.name, row.isPlayer, row.isRecorder, row.uuid)
                if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) EntityActions.flyTo(context, ref(row))
            }
            if (ImGui.beginPopupContextItem("entity-menu")) {
                EntityActions.menu(context, ref(row))
                ImGui.endPopup()
            }
        } finally {
            ImGui.popID()
        }
    }

    private fun ref(row: Row): EntityRef =
        EntityRef(row.id, row.name, row.isPlayer, row.isRecorder, row.uuid, row.x, row.y, row.z)

    private fun toggle(key: String) {
        if (!expanded.remove(key)) expanded.add(key)
    }

    private fun node(
        id: String,
        depth: Int,
        icon: Icon,
        label: String,
        color: Int,
        selected: Boolean,
        expandable: Boolean,
        trailing: String,
        open: Boolean = false,
        iconColor: Int = EditorTheme.TEXT_MUTED.u32,
        eye: Boolean? = null,
        onEye: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val height = ROW_HEIGHT
        var chevronHit = false
        var eyeHit = false
        val pressed = Widgets.row(id, height, selected) { x, y, width, hovered ->
            val list = ImGui.getWindowDrawList()
            val indent = EditorFonts.px(8f) + depth * EditorFonts.px(18f)
            val chevron = EditorFonts.px(10f)
            if (expandable) {
                Icons.draw(
                    list,
                    if (open) Icon.CHEVRON_DOWN else Icon.CHEVRON_RIGHT,
                    x + indent,
                    y + (height - chevron) / 2f,
                    chevron,
                    EditorTheme.TEXT_DIM.u32
                )
                val cx = ImGui.getMousePosX()
                val cy = ImGui.getMousePosY()
                if (hovered && cx >= x + indent - EditorFonts.px(4f) && cx <= x + indent + chevron + EditorFonts.px(6f) && cy >= y && cy <= y + height && ImGui.isMouseClicked(
                        ImGuiMouseButton.Left
                    )
                ) chevronHit = true
            }
            val iconSize = EditorFonts.px(14f)
            val iconX = x + indent + chevron + EditorFonts.px(6f)
            Icons.draw(list, icon, iconX, y + (height - iconSize) / 2f, iconSize, iconColor)
            val textX = iconX + iconSize + EditorFonts.px(7f)
            val eyeSize = EditorFonts.px(14f)
            val eyeX = x + width - eyeSize - EditorFonts.px(8f)
            var rightEdge = x + width - EditorFonts.px(8f)
            if (eye != null) {
                val show = hovered || !eye
                if (show) Icons.draw(
                    list,
                    if (eye) Icon.EYE else Icon.EYE_OFF,
                    eyeX,
                    y + (height - eyeSize) / 2f,
                    eyeSize,
                    if (eye) EditorTheme.TEXT_DIM.u32 else EditorTheme.WARNING.u32
                )
                rightEdge = eyeX - EditorFonts.px(6f)
                val cx = ImGui.getMousePosX()
                val cy = ImGui.getMousePosY()
                if (hovered && cx >= eyeX - EditorFonts.px(4f) && cx <= eyeX + eyeSize + EditorFonts.px(4f) && cy >= y && cy <= y + height && ImGui.isMouseClicked(
                        ImGuiMouseButton.Left
                    )
                ) {
                    onEye?.invoke(!eye)
                    eyeHit = true
                }
            }
            if (trailing.isNotEmpty()) {
                EditorFonts.with(EditorFonts.small) {
                    val trailingWidth = Widgets.textWidth(trailing)
                    list.addText(
                        rightEdge - trailingWidth,
                        y + (height - ImGui.getFontSize()) / 2f,
                        EditorTheme.TEXT_DIM.u32,
                        trailing
                    )
                    rightEdge -= trailingWidth + EditorFonts.px(8f)
                }
            }
            val font = if (selected) EditorFonts.bodyMedium else EditorFonts.body
            val clipped = EditorFonts.with(font) { Widgets.clip(label, maxOf(EditorFonts.px(20f), rightEdge - textX)) }
            list.addText(
                font,
                ImGui.getFontSize().toInt(),
                textX,
                y + (height - ImGui.getFontSize()) / 2f,
                if (selected) EditorTheme.TEXT.u32 else color,
                clipped
            )
        }
        if (eyeHit) return false
        if (chevronHit) {
            toggle(id)
            return false
        }
        if (pressed && expandable && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
            toggle(id)
            return false
        }
        return pressed
    }

    private fun collect(shadow: ShadowClient, camera: CameraPose): List<Row> {
        val result = ArrayList<Row>(shadow.entities.size + 1)
        val local = shadow.localPlayer
        if (local.hasPosition) {
            result += Row(
                local.entityId,
                local.name ?: "Recorder",
                "player",
                true,
                true,
                healthText(local.health.toDouble()),
                camera.position.distance(local.x, local.y, local.z),
                local.x,
                local.y,
                local.z,
                local.uuid?.toString()
            )
        }
        for (entity in shadow.entities.values()) {
            if (entity.dead || entity.id == local.entityId) continue
            val profile = entity.uuid?.let { shadow.players.profile(it) }
            val name = when {
                profile != null -> profile.name
                entity.isPlayer -> "Player #${entity.id}"
                else -> kindLabel(entity)
            }
            val health = entity.metadata[HEALTH_INDEX]?.takeIf { it.type == MetadataEntry.FLOAT }
                ?.let { healthText((it.value as Float).toDouble()) } ?: ""
            result += Row(
                entity.id,
                name,
                kindLabel(entity),
                entity.isPlayer,
                false,
                health,
                camera.position.distance(entity.x, entity.y, entity.z),
                entity.x,
                entity.y,
                entity.z,
                entity.uuid?.toString()
            )
        }
        return result
    }

    private fun healthText(health: Double): String = if (health <= 0.0) "" else String.format("%.0f", health)

    private fun kindLabel(entity: ShadowEntity): String = when (entity.kind) {
        EntityKind.PLAYER -> "player"
        EntityKind.MOB -> MOB_NAMES[entity.type] ?: "mob ${entity.type}"
        EntityKind.OBJECT -> OBJECT_NAMES[entity.type] ?: "object ${entity.type}"
        EntityKind.PAINTING -> "painting"
        EntityKind.EXPERIENCE_ORB -> "xp orb"
        EntityKind.GLOBAL -> "lightning"
    }

    companion object {
        val ROW_HEIGHT: Float get() = EditorFonts.px(24f)
        const val HEALTH_INDEX = 6
        val MARKER_ICONS = mapOf(
            MarkerKind.MOMENT to Icon.BOOKMARK,
            MarkerKind.SHOT to Icon.FILM,
            MarkerKind.PLAYER to Icon.PERSON,
            MarkerKind.EVENT to Icon.TARGET,
            MarkerKind.CAMERA to Icon.CAMERA,
        )
        const val MAX_ENTITIES = 150
        val REFRESH_NANOS = 500_000_000L
        val LANE_ICONS = mapOf(
            LaneKind.SPEED to Icon.GAUGE,
            LaneKind.FOV to Icon.APERTURE,
            LaneKind.TIME_OF_DAY to Icon.SUN,
            LaneKind.SHAKE to Icon.WAVE,
            LaneKind.SHAKE_FREQUENCY to Icon.WAVE,
            LaneKind.VIEW to Icon.EYE,
            LaneKind.FREEZE to Icon.SNOWFLAKE,
            LaneKind.POSE to Icon.PERSON,
            LaneKind.FOCUS to Icon.FOCUS,
            LaneKind.TEXTURE_PACK to Icon.PACKAGE,
        )
        val MOB_NAMES = mapOf(
            48 to "mob",
            49 to "monster",
            50 to "creeper",
            51 to "skeleton",
            52 to "spider",
            53 to "giant",
            54 to "zombie",
            55 to "slime",
            56 to "ghast",
            57 to "zombie pigman",
            58 to "enderman",
            59 to "cave spider",
            60 to "silverfish",
            61 to "blaze",
            62 to "magma cube",
            63 to "ender dragon",
            64 to "wither",
            65 to "bat",
            66 to "witch",
            67 to "endermite",
            68 to "guardian",
            90 to "pig",
            91 to "sheep",
            92 to "cow",
            93 to "chicken",
            94 to "squid",
            95 to "wolf",
            96 to "mooshroom",
            97 to "snow golem",
            98 to "ocelot",
            99 to "iron golem",
            100 to "horse",
            101 to "rabbit",
            120 to "villager",
        )
        val OBJECT_NAMES = mapOf(
            1 to "boat",
            2 to "item",
            10 to "minecart",
            50 to "tnt",
            51 to "ender crystal",
            60 to "arrow",
            61 to "snowball",
            62 to "egg",
            63 to "fireball",
            64 to "small fireball",
            65 to "ender pearl",
            66 to "wither skull",
            70 to "falling block",
            71 to "item frame",
            72 to "eye of ender",
            73 to "potion",
            75 to "exp bottle",
            76 to "firework",
            77 to "leash knot",
            78 to "armor stand",
            90 to "fishing hook",
        )
    }
}
