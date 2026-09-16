package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.camera.CameraMode
import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.editor.*
import gg.sona.afterimage.editor.commands.*
import gg.sona.afterimage.world.EntityKind
import gg.sona.afterimage.world.EntityState
import gg.sona.afterimage.world.GameNames
import gg.sona.afterimage.world.WorldState
import imgui.ImGui
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiStyleVar
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
    private val toggled = context.ui.set("editor.hierarchy.toggled")
    private var rows: List<Row> = emptyList()
    private var lastRefreshNanos = 0L

    override fun content(frame: FrameContext) {
        val session = context.session
        val replay = session?.replay
        if (session == null || replay == null) {
            Widgets.emptyState("No replay open", "Open one from the library", Icon.LIST)
            return
        }
        toolbar()
        if (frame.nowNanos - lastRefreshNanos > REFRESH_NANOS || rows.isEmpty()) {
            lastRefreshNanos = frame.nowNanos
            rows = collect(replay.world, replay.names, context.host.camera.currentPose())
        }
        val query = filter.get().trim().lowercase()
        ImGui.beginChild("##hierarchy-tree", 0f, 0f, false, ImGuiWindowFlags.None)
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, EditorFonts.px(1f))
        try {
            project(session, query)
            cameraPath(session, query)
            tracks(session, query)
            clips(session, query)
            markers(session, query)
            moments(session, query)
            people(session, query)
            ImGui.dummy(0f, EditorFonts.px(8f))
        } finally {
            ImGui.popStyleVar()
        }
        ImGui.endChild()
    }

    private fun toolbar() {
        val button = ImGui.getFrameHeight()
        val gap = EditorFonts.px(6f)
        Widgets.search("##hierarchy-search", filter, "Filter", ImGui.getContentRegionAvailX() - button - gap)
        ImGui.sameLine(0f, gap)
        if (Widgets.iconButton("hierarchy-more", Icon.MORE, button, "Hierarchy options", iconScale = 0.55f, color = EditorTheme.TEXT_MUTED.u32)) {
            ImGui.openPopup("hierarchy-menu")
        }
        if (Widgets.beginPopup("hierarchy-menu")) {
            if (Menus.item("Expand all")) for (key in SECTIONS) toggled.toggle(key, key !in OPEN_BY_DEFAULT)
            if (Menus.item("Collapse all")) for (key in SECTIONS) toggled.toggle(key, key in OPEN_BY_DEFAULT)
            ImGui.separator()
            if (Menus.item("Show all hidden entities", "", enabled = context.visuals.hiddenEntities.isNotEmpty())) context.visuals.hiddenEntities.clear()
            Widgets.endPopup()
        }
        ImGui.dummy(0f, EditorFonts.px(4f))
    }

    private fun project(session: EditorSession, query: String) {
        val name = session.project.name
        if (query.isNotEmpty() && !name.lowercase().contains(query)) return
        val selected = context.inspect == InspectTarget.Project && session.selection.isEmpty
        val clicked = item(
            "project",
            Icon.FOLDER,
            EditorTheme.ACCENT_TEXT.u32,
            name,
            EditorTheme.TEXT.u32,
            selected,
            indent = false,
            badge = if (session.project.dirty) "edited" else null,
            badgeColor = EditorTheme.WARNING.u32,
            badgeBackground = EditorTheme.WARNING.u32(0.16f),
        )
        if (clicked) {
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
        val open = isOpen("path") || query.isNotEmpty()
        val laneSelected = context.inspect == InspectTarget.Lane(LaneKind.CAMERA) && session.selection.isEmpty
        val clicked = section(
            "path",
            "Camera path",
            "${keyframes.size}",
            expandable = keyframes.isNotEmpty(),
            open = open,
            selected = laneSelected,
            selectable = true,
            dim = lane.muted,
            eye = !lane.muted,
        ) { visible -> session.execute(SetLaneState(LaneKind.CAMERA, lane.copy(muted = !visible))) }
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
                val clickedRow = item(
                    "kf",
                    Icon.KEYFRAME,
                    EditorTheme.modeColor(keyframe.mode).u32,
                    TimeFormat.clock(keyframe.timeNanos),
                    if (lane.muted) EditorTheme.TEXT_MUTED.u32 else EditorTheme.TEXT.u32,
                    selected,
                    trailing = keyframe.mode.label,
                    dot = if (atPlayhead) EditorTheme.SELECTION.u32 else null,
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
                    if (!selected) session.selection = Selection(keyframeTimes = setOf(keyframe.timeNanos))
                    ImGui.openPopup("kf-menu")
                }
                if (Widgets.beginPopup("kf-menu")) {
                    val targets = session.selection.keyframeTimes.ifEmpty { setOf(keyframe.timeNanos) }
                    if (Menus.item("Go to keyframe")) session.replay?.seek(keyframe.timeNanos)
                    if (Menus.item("Frame in scene", "F")) context.host.camera.frame(
                        keyframe.pose.position.x,
                        keyframe.pose.position.y,
                        keyframe.pose.position.z,
                        1.5
                    )
                    ImGui.separator()
                    if (Menus.item(if (targets.size > 1) "Delete ${targets.size} keyframes" else "Delete keyframe")) {
                        session.execute(RemoveKeyframes(targets))
                        session.selection = Selection.NONE
                    }
                    Widgets.endPopup()
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
        val visible = lanes.filter { (kind, _, count) -> count > 0 || kind in context.timeline.shownLanes }
            .filter { query.isEmpty() || it.second.lowercase().contains(query) }
        if (visible.isEmpty()) return
        val open = isOpen("tracks") || query.isNotEmpty()
        section("tracks", "Tracks", "${visible.size}", expandable = true, open = open)
        if (!open) return
        for ((kind, label, count) in visible) {
            val state = session.project.lane(kind)
            val selected = context.inspect == InspectTarget.Lane(kind) && session.selection.isEmpty
            ImGui.pushID(kind.name)
            try {
                val clicked = item(
                    "lane",
                    LANE_ICONS[kind] ?: Icon.SLIDERS,
                    if (state.muted) EditorTheme.TEXT_DIM.u32 else EditorTheme.TEXT_MUTED.u32,
                    label,
                    if (state.muted) EditorTheme.TEXT_MUTED.u32 else EditorTheme.TEXT.u32,
                    selected,
                    trailing = if (count == 0) "empty" else "$count",
                    eye = !state.muted,
                ) { visibleNow -> session.execute(SetLaneState(kind, state.copy(muted = !visibleNow))) }
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
        val open = isOpen("clips") || query.isNotEmpty()
        section("clips", "Clips", "${clips.size}", expandable = clips.isNotEmpty(), open = open, dim = clips.isEmpty())
        if (!open) return
        for (clip in matching) {
            val selected = clip.id in session.selection.clipIds
            ImGui.pushID(clip.id.toString())
            try {
                if (item(
                        "clip",
                        Icon.FILM,
                        EditorTheme.CLIP_SELECTED.u32,
                        clip.title,
                        EditorTheme.TEXT.u32,
                        selected,
                        trailing = TimeFormat.short(clip.durationNanos),
                    )
                ) {
                    session.selection = session.selection.withClip(clip.id, ImGui.getIO().keyCtrl)
                    context.selectedEntityId = null
                    if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) ClipActions.play(session, clip)
                }
                if (Widgets.beginContextPopup("clip-menu")) {
                    if (Menus.item("Play")) ClipActions.play(session, clip)
                    if (Menus.item("Go to start")) session.replay?.seek(clip.startNanos)
                    ImGui.separator()
                    if (Menus.item("Delete")) {
                        session.execute(RemoveClip(clip.id))
                        context.clips.delete(clip.id)
                        session.selection = Selection.NONE
                    }
                    Widgets.endPopup()
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
        val open = isOpen("markers") || query.isNotEmpty()
        section("markers", "Markers", "${markers.size}", expandable = markers.isNotEmpty(), open = open, dim = markers.isEmpty())
        if (!open) return
        for (marker in matching) {
            val selected = marker.id in session.selection.markerIds
            ImGui.pushID(marker.id.toString())
            try {
                if (item(
                        "marker",
                        MARKER_ICONS[marker.kind] ?: Icon.MARKER,
                        Widgets.rgbToU32(marker.color),
                        marker.label,
                        EditorTheme.TEXT.u32,
                        selected,
                        trailing = TimeFormat.short(marker.nanos),
                    )
                ) {
                    session.selection = session.selection.withMarker(marker.id, ImGui.getIO().keyCtrl)
                    context.selectedEntityId = null
                    if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) session.replay?.seek(marker.nanos)
                }
                if (Widgets.beginContextPopup("marker-menu")) {
                    if (Menus.item("Go to marker")) session.replay?.seek(marker.nanos)
                    if (ImGui.beginMenu("Type")) {
                        for (kind in MarkerKind.entries) if (Menus.item(kind.label, "", marker.kind == kind)) session.execute(
                            ReplaceMarker(
                                marker.id,
                                marker.copy(kind = kind, color = if (marker.kind == kind) marker.color else kind.color)
                            )
                        )
                        ImGui.endMenu()
                    }
                    ImGui.separator()
                    if (Menus.item("Delete")) {
                        session.execute(RemoveMarker(marker.id))
                        session.selection = Selection.NONE
                    }
                    Widgets.endPopup()
                }
            } finally {
                ImGui.popID()
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
        val open = isOpen("moments") || query.isNotEmpty()
        val kept = session.project.moments.size
        section(
            "moments",
            "Moments",
            if (all.size > kept) "$kept + ${all.size - kept}" else "$kept",
            expandable = true,
            open = open
        )
        if (!open) return
        for (moment in matching) {
            val selected = moment.id in session.selection.momentIds
            val isKept = MomentActions.isKept(session, moment)
            ImGui.pushID(moment.id.toString())
            try {
                if (item(
                        "moment",
                        Icon.BOOKMARK,
                        Widgets.rgbToU32(moment.kind.color, if (isKept) 1f else 0.55f),
                        moment.label,
                        if (isKept) EditorTheme.TEXT.u32 else EditorTheme.TEXT_MUTED.u32,
                        selected,
                        trailing = TimeFormat.short(moment.peakNanos),
                    )
                ) {
                    session.selection = session.selection.withMoment(moment.id, ImGui.getIO().keyCtrl)
                    context.selectedEntityId = null
                    if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) MomentActions.play(session, moment)
                    else session.replay?.seek(moment.peakNanos)
                }
                if (ImGui.isItemClicked(ImGuiMouseButton.Right)) momentRename.set(moment.label)
                if (Widgets.beginContextPopup("moment-menu")) {
                    MomentActions.menu(context, session, moment, momentRename)
                    Widgets.endPopup()
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
            val open = isOpen("players") || query.isNotEmpty()
            section("players", "Players", "${players.size}", expandable = players.isNotEmpty(), open = open, dim = players.isEmpty())
            if (open) for (row in matchingPlayers) entityRow(session, row, if (row.isRecorder) Icon.USER else Icon.PERSON)
        }
        if (matchingOthers.isNotEmpty() || query.isEmpty()) {
            val open = isOpen("entities") || query.isNotEmpty()
            section("entities", "Entities", "${others.size}", expandable = others.isNotEmpty(), open = open, dim = others.isEmpty())
            if (open) for (row in matchingOthers.take(MAX_ENTITIES)) entityRow(session, row, Icon.CUBE)
            if (open && matchingOthers.size > MAX_ENTITIES) {
                ImGui.dummy(0f, EditorFonts.px(2f))
                ImGui.setCursorPosX(ImGui.getCursorPosX() + CHILD_INDENT)
                Widgets.smallText("${matchingOthers.size - MAX_ENTITIES} more, filter to find them", EditorTheme.TEXT_DIM.u32)
            }
        }
    }

    private fun entityRow(session: EditorSession, row: Row, icon: Icon) {
        val selected = context.selectedEntityId == row.id
        val hidden = context.visuals.isHidden(row.id)
        val settings = context.host.camera.settings
        val targeted = if (row.isRecorder) settings.targetsRecorder() else settings.targetEntityId == row.id
        val viewing = targeted && settings.mode != CameraMode.FREE
        val trailing = when {
            viewing -> settings.mode.label
            row.distance < 1000 -> String.format("%.0f m", row.distance)
            else -> "far"
        }
        ImGui.pushID(row.id)
        try {
            val clicked = item(
                "entity",
                icon,
                when {
                    row.isRecorder -> EditorTheme.ACCENT_TEXT.u32
                    hidden -> EditorTheme.TEXT_DIM.u32
                    else -> EditorTheme.TEXT_MUTED.u32
                },
                row.name,
                if (hidden) EditorTheme.TEXT_DIM.u32 else EditorTheme.TEXT.u32,
                selected,
                trailing = trailing,
                trailingColor = if (viewing) EditorTheme.ACCENT_TEXT.u32 else EditorTheme.TEXT_DIM.u32,
                eye = if (row.isRecorder) null else !hidden,
            ) { visible ->
                val set = context.visuals.hiddenEntities
                if (visible) set.remove(row.id) else set.add(row.id)
            }
            if (clicked) {
                context.selectEntity(row.id, row.name, row.isPlayer, row.isRecorder, row.uuid)
                if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) EntityActions.flyTo(context, ref(row))
            }
            if (Widgets.beginContextPopup("entity-menu")) {
                EntityActions.menu(context, ref(row))
                Widgets.endPopup()
            }
        } finally {
            ImGui.popID()
        }
    }

    private fun ref(row: Row): EntityRef =
        EntityRef(row.id, row.name, row.isPlayer, row.isRecorder, row.uuid, row.x, row.y, row.z)

    private fun toggle(key: String) {
        if (!toggled.remove(key)) toggled.add(key)
    }

    private fun isOpen(key: String): Boolean = (key in OPEN_BY_DEFAULT) != (key in toggled)

    private fun section(
        key: String,
        title: String,
        count: String,
        expandable: Boolean,
        open: Boolean,
        selected: Boolean = false,
        selectable: Boolean = false,
        dim: Boolean = false,
        eye: Boolean? = null,
        onEye: ((Boolean) -> Unit)? = null,
    ): Boolean {
        if (ImGui.getCursorPosY() > EditorFonts.px(4f)) ImGui.dummy(0f, EditorFonts.px(8f))
        val height = SECTION_HEIGHT
        var chevronHit = false
        var eyeHit = false
        val pressed = Widgets.row("section-$key", height, selected) { x, y, width, hovered ->
            val list = ImGui.getWindowDrawList()
            val chevron = EditorFonts.px(9f)
            val chevronX = x + EditorFonts.px(6f)
            if (expandable) {
                Icons.draw(
                    list,
                    if (open) Icon.CHEVRON_DOWN else Icon.CHEVRON_RIGHT,
                    chevronX,
                    y + (height - chevron) / 2f,
                    chevron,
                    if (hovered) EditorTheme.TEXT_MUTED.u32 else EditorTheme.TEXT_DIM.u32
                )
                if (hovered && ImGui.getMousePosX() < x + CHILD_INDENT && ImGui.isMouseClicked(ImGuiMouseButton.Left)) chevronHit = true
            }
            val textColor = when {
                selected -> EditorTheme.TEXT.u32
                dim -> EditorTheme.TEXT_DIM.u32
                hovered -> EditorTheme.TEXT_MUTED.u32
                else -> EditorTheme.TEXT_DIM.u32
            }
            EditorFonts.with(EditorFonts.label) {
                list.addText(x + CHILD_INDENT, y + (height - ImGui.getFontSize()) / 2f, textColor, title.uppercase())
            }
            var right = x + width - EditorFonts.px(8f)
            if (eye != null && (hovered || !eye)) {
                val eyeSize = EditorFonts.px(13f)
                val eyeX = right - eyeSize
                Icons.draw(
                    list,
                    if (eye) Icon.EYE else Icon.EYE_OFF,
                    eyeX,
                    y + (height - eyeSize) / 2f,
                    eyeSize,
                    if (eye) EditorTheme.TEXT_MUTED.u32 else EditorTheme.WARNING.u32
                )
                if (hovered && ImGui.getMousePosX() >= eyeX - EditorFonts.px(6f) && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
                    onEye?.invoke(!eye)
                    eyeHit = true
                }
                right = eyeX - EditorFonts.px(8f)
            }
            EditorFonts.with(EditorFonts.small) {
                val countWidth = Widgets.textWidth(count)
                list.addText(right - countWidth, y + (height - ImGui.getFontSize()) / 2f, EditorTheme.TEXT_DIM.u32, count)
            }
        }
        if (eyeHit) return false
        if (chevronHit || (pressed && !selectable)) {
            if (expandable) toggle(key)
            return false
        }
        return pressed && selectable
    }

    private fun item(
        id: String,
        icon: Icon,
        iconColor: Int,
        label: String,
        color: Int,
        selected: Boolean,
        indent: Boolean = true,
        trailing: String? = null,
        trailingColor: Int = EditorTheme.TEXT_DIM.u32,
        badge: String? = null,
        badgeColor: Int = EditorTheme.TEXT_MUTED.u32,
        badgeBackground: Int = EditorTheme.CONTROL.u32,
        dot: Int? = null,
        eye: Boolean? = null,
        onEye: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val height = ROW_HEIGHT
        var eyeHit = false
        val pressed = Widgets.row(id, height, selected) { x, y, width, hovered ->
            val list = ImGui.getWindowDrawList()
            val iconSize = EditorFonts.px(14f)
            val iconX = x + if (indent) CHILD_INDENT else EditorFonts.px(8f)
            Icons.draw(list, icon, iconX, y + (height - iconSize) / 2f, iconSize, iconColor)
            val textX = iconX + iconSize + EditorFonts.px(8f)
            var right = x + width - EditorFonts.px(8f)
            val centerY = y + height / 2f
            if (eye != null && (hovered || !eye)) {
                val eyeSize = EditorFonts.px(13f)
                val eyeX = right - eyeSize
                Icons.draw(
                    list,
                    if (eye) Icon.EYE else Icon.EYE_OFF,
                    eyeX,
                    y + (height - eyeSize) / 2f,
                    eyeSize,
                    if (eye) EditorTheme.TEXT_MUTED.u32 else EditorTheme.WARNING.u32
                )
                if (hovered && ImGui.getMousePosX() >= eyeX - EditorFonts.px(6f) && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
                    onEye?.invoke(!eye)
                    eyeHit = true
                }
                right = eyeX - EditorFonts.px(8f)
            }
            if (badge != null) {
                right -= Widgets.drawBadge(list, badge, right, centerY, badgeColor, badgeBackground) + EditorFonts.px(8f)
            }
            if (dot != null) {
                val radius = EditorFonts.px(3f)
                list.addCircleFilled(right - radius, centerY, radius, dot, 12)
                right -= radius * 2f + EditorFonts.px(8f)
            }
            if (!trailing.isNullOrEmpty()) EditorFonts.with(EditorFonts.small) {
                val trailingWidth = Widgets.textWidth(trailing)
                list.addText(right - trailingWidth, y + (height - ImGui.getFontSize()) / 2f, trailingColor, trailing)
                right -= trailingWidth + EditorFonts.px(8f)
            }
            val font = if (selected) EditorFonts.bodyMedium else EditorFonts.body
            val clipped = EditorFonts.with(font) { Widgets.clip(label, maxOf(EditorFonts.px(20f), right - textX)) }
            list.addText(
                font,
                ImGui.getFontSize().toInt(),
                textX,
                y + (height - ImGui.getFontSize()) / 2f,
                if (selected) EditorTheme.TEXT.u32 else color,
                clipped
            )
        }
        return pressed && !eyeHit
    }

    private fun collect(shadow: WorldState, names: GameNames, camera: CameraPose): List<Row> {
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
                else -> kindLabel(names, entity)
            }
            val health = entity.health.takeIf { !it.isNaN() }?.let { healthText(it.toDouble()) } ?: ""
            result += Row(
                entity.id,
                name,
                kindLabel(names, entity),
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

    private fun kindLabel(names: GameNames, entity: EntityState): String =
        if (entity.kind == EntityKind.PLAYER) "player" else names.entityLabel(entity.kind, entity.type, entity.id)

    companion object {
        val ROW_HEIGHT: Float get() = EditorFonts.px(24f)
        val SECTION_HEIGHT: Float get() = EditorFonts.px(22f)
        val CHILD_INDENT: Float get() = EditorFonts.px(20f)
        val MARKER_ICONS = mapOf(
            MarkerKind.MOMENT to Icon.BOOKMARK,
            MarkerKind.SHOT to Icon.FILM,
            MarkerKind.PLAYER to Icon.PERSON,
            MarkerKind.EVENT to Icon.TARGET,
            MarkerKind.CAMERA to Icon.CAMERA,
        )
        const val MAX_ENTITIES = 150
        val SECTIONS = listOf("path", "tracks", "clips", "markers", "moments", "players", "entities")
        val OPEN_BY_DEFAULT = setOf("path", "players", "entities", "clips", "markers")
        val REFRESH_NANOS = 500_000_000L
        val LANE_ICONS = mapOf(
            LaneKind.SPEED to Icon.GAUGE,
            LaneKind.FOV to Icon.APERTURE,
            LaneKind.TIME_OF_DAY to Icon.SUN,
            LaneKind.SHAKE to Icon.WAVE,
            LaneKind.SHAKE_FREQUENCY to Icon.WAVE,
            LaneKind.VIEW to Icon.EYE,
            LaneKind.FREEZE to Icon.SNOWFLAKE,
            LaneKind.FOCUS to Icon.FOCUS,
            LaneKind.TEXTURE_PACK to Icon.PACKAGE,
        )
    }
}
