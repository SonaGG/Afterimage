package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.EditorSession
import gg.sona.afterimage.editor.Moment
import gg.sona.afterimage.editor.MomentOrigin
import gg.sona.afterimage.editor.Selection
import gg.sona.afterimage.index.EntityTrack
import gg.sona.afterimage.index.IndexEventKind
import gg.sona.afterimage.index.Items
import gg.sona.afterimage.index.ReplayIndex
import gg.sona.afterimage.index.query.EventLabels
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiMouseCursor
import imgui.type.ImString
import java.util.*
import kotlin.math.abs
import kotlin.math.sqrt

class GameLanes(private val context: EditorContext, private val geometry: TimelineGeometry) {
    class PlayerRow(
        val name: String,
        val tracks: List<EntityTrack>,
        val isRecorder: Boolean,
        val color: EditorTheme.Rgb
    ) {
        val ids: Set<Int> = tracks.map { it.entityId }.toSet()
        var expanded = false
        val events = ArrayList<Int>()
        val presenceNanos: Long get() = tracks.sumOf { it.length.toLong() }
    }

    enum class SubRow(val label: String) { PRESENCE("Presence"), HEALTH("Health"), SPEED("Speed"), ACTIONS("Actions") }

    enum class WorldRow(val label: String, val icon: Icon) {
        BLOCKS("Blocks", Icon.CUBE), EXPLOSIONS("Explosions", Icon.WAVE), PROJECTILES("Projectiles", Icon.ARROW_RIGHT),
        SOUNDS("Sounds", Icon.VOLUME), DIMENSION("Dimension", Icon.GLOBE)
    }

    private var cachedIndex: ReplayIndex? = null
    private var labels: EventLabels? = null
    private var rows: List<PlayerRow> = emptyList()
    private var explosions = IntArray(0)
    private var projectiles = IntArray(0)
    private var sounds = IntArray(0)
    private val projectileEnds = HashMap<Int, Long>()
    private var dimensionSegments: List<Triple<Long, Long, Int>> = emptyList()
    private val hiddenWorldRows: UiPreferences.PersistedSet get() = context.ui.hiddenWorldRows
    private var showAllPlayers: Boolean
        get() = context.ui.showAllPlayers
        set(value) {
            context.ui.showAllPlayers = value
        }
    private val renameBuffer = ImString("", 64)

    val playerRows: List<PlayerRow> get() = rows

    val index: ReplayIndex? get() = cachedIndex

    fun prepare(session: EditorSession) {
        val index = session.events.index
        if (index === cachedIndex) return
        cachedIndex = index
        rows = emptyList()
        explosions = IntArray(0)
        projectiles = IntArray(0)
        sounds = IntArray(0)
        projectileEnds.clear()
        dimensionSegments = emptyList()
        labels = null
        if (index == null) return
        labels = EventLabels(index)
        val byName = LinkedHashMap<String, ArrayList<EntityTrack>>()
        for (track in index.tracks) {
            if (!track.isPlayer) continue
            val name = track.name ?: continue
            if (!ReplayIndex.isUsername(name)) continue
            byName.getOrPut(name) { ArrayList() } += track
        }
        val built = byName.entries.map { (name, tracks) ->
            val recorder = tracks.any { it.isRecorder }
            PlayerRow(name, tracks, recorder, PlayerColors.of(index, name, recorder))
        }.filter { it.isRecorder || it.presenceNanos >= MIN_PRESENCE_TICKS }
            .sortedWith(compareByDescending<PlayerRow> { it.isRecorder }.thenByDescending { it.presenceNanos })
        val byId = HashMap<Int, PlayerRow>()
        for (row in built) for (id in row.ids) byId[id] = row
        val events = index.events
        val explosionList = ArrayList<Int>()
        val projectileList = ArrayList<Int>()
        val soundList = ArrayList<Int>()
        for (i in 0 until events.size) {
            when (events.kindAt(i)) {
                IndexEventKind.EXPLOSION -> explosionList += i
                IndexEventKind.PROJECTILE_SPAWN -> projectileList += i
                IndexEventKind.PROJECTILE_END -> projectileEnds[events.a[i]] = events.nanos[i]
                IndexEventKind.SOUND -> soundList += i
                IndexEventKind.KILL, IndexEventKind.DEATH, IndexEventKind.HURT, IndexEventKind.CRIT, IndexEventKind.SWING,
                IndexEventKind.USE_START, IndexEventKind.USE_END, IndexEventKind.EAT, IndexEventKind.PICKUP,
                IndexEventKind.EQUIP, IndexEventKind.EFFECT, IndexEventKind.ATTACK -> {
                    byId[events.a[i]]?.events?.add(i)
                    val kind = events.kindAt(i)
                    if (kind == IndexEventKind.KILL || kind == IndexEventKind.HURT) byId[events.b[i]]?.takeIf { it !== byId[events.a[i]] }?.events?.add(
                        i
                    )
                }

                else -> Unit
            }
        }
        explosions = explosionList.toIntArray()
        projectiles = projectileList.toIntArray()
        sounds = soundList.toIntArray()
        rows = built
        val segments = ArrayList<Triple<Long, Long, Int>>()
        var start = 0
        for (tick in 1..index.dimensions.size) {
            if (tick == index.dimensions.size || index.dimensions[tick] != index.dimensions[start]) {
                segments += Triple(index.nanosOf(start), index.nanosOf(tick), index.dimensions[start])
                start = tick
            }
        }
        dimensionSegments = segments
    }

    // Layout

    fun visibleRows(): List<PlayerRow> = if (showAllPlayers) rows else rows.take(MAX_ROWS)

    fun playerRowCount(): Int = visibleRows().sumOf { if (it.expanded) 4 else 1 }

    fun worldRows(): List<WorldRow> = WorldRow.entries.filter {
        it !in hiddenWorldRows && (it != WorldRow.DIMENSION || dimensionSegments.size > 1)
    }

    fun playerRowAt(offsetY: Float, rowHeight: Float): Pair<PlayerRow, SubRow>? {
        var y = 0f
        for (row in visibleRows()) {
            val subRows = if (row.expanded) SubRow.entries else listOf(SubRow.PRESENCE)
            for (sub in subRows) {
                if (offsetY >= y && offsetY < y + rowHeight) return row to sub
                y += rowHeight
            }
        }
        return null
    }

    fun togglePlayer(offsetY: Float, rowHeight: Float) {
        playerRowAt(offsetY, rowHeight)?.first?.let { it.expanded = !it.expanded }
    }

    // Headers

    fun drawPlayerHeaders(drawList: ImDrawList, top: Float, rowHeight: Float, headerX: Float, originX: Float) {
        var y = top
        val chevron = EditorFonts.px(9f)
        for (row in visibleRows()) {
            val font = if (row.isRecorder) EditorFonts.bodyMedium else EditorFonts.body
            Icons.draw(
                drawList,
                if (row.expanded) Icon.CHEVRON_DOWN else Icon.CHEVRON_RIGHT,
                headerX + EditorFonts.px(31f),
                y + (rowHeight - chevron) / 2f,
                chevron,
                EditorTheme.TEXT_DIM.u32
            )
            drawList.addCircleFilled(
                headerX + EditorFonts.px(48f),
                y + rowHeight / 2f,
                EditorFonts.px(3.5f),
                row.color.u32,
                10
            )
            EditorFonts.with(font) {
                drawList.addText(
                    font, ImGui.getFontSize().toInt(),
                    headerX + EditorFonts.px(56f), y + (rowHeight - ImGui.getFontSize()) / 2f,
                    EditorTheme.TEXT.u32,
                    Widgets.clip(row.name, originX - headerX - EditorFonts.px(64f) - EditorFonts.px(30f))
                )
            }
            y += rowHeight
            if (!row.expanded) continue
            for (sub in SubRow.entries) {
                if (sub == SubRow.PRESENCE) continue
                EditorFonts.with(EditorFonts.small) {
                    drawList.addText(
                        headerX + EditorFonts.px(56f), y + (rowHeight - ImGui.getFontSize()) / 2f,
                        EditorTheme.TEXT_MUTED.u32, sub.label
                    )
                }
                y += rowHeight
            }
        }
    }

    fun drawWorldHeaders(drawList: ImDrawList, top: Float, rowHeight: Float, headerX: Float) {
        var y = top
        val iconSize = EditorFonts.px(11f)
        for (row in worldRows()) {
            Icons.draw(
                drawList,
                row.icon,
                headerX + EditorFonts.px(34f),
                y + (rowHeight - iconSize) / 2f,
                iconSize,
                EditorTheme.TEXT_DIM.u32
            )
            EditorFonts.with(EditorFonts.small) {
                drawList.addText(
                    headerX + EditorFonts.px(50f), y + (rowHeight - ImGui.getFontSize()) / 2f,
                    EditorTheme.TEXT_MUTED.u32, row.label
                )
            }
            y += rowHeight
        }
    }

    // Players lane

    fun drawPlayers(drawList: ImDrawList, top: Float, rowHeight: Float) {
        val index = cachedIndex ?: return
        var y = top
        val left = geometry.leftNanos - Nanos.PER_SECOND
        val right = geometry.rightNanos + Nanos.PER_SECOND
        for (row in visibleRows()) {
            drawPresence(drawList, index, row, y, rowHeight, left, right)
            drawPlayerGlyphs(drawList, index, row, y, rowHeight, left, right, false)
            y += rowHeight
            if (!row.expanded) continue
            drawHealth(drawList, index, row, y, rowHeight)
            y += rowHeight
            drawSpeed(drawList, index, row, y, rowHeight)
            y += rowHeight
            drawPlayerGlyphs(drawList, index, row, y, rowHeight, left, right, true)
            y += rowHeight
        }
    }

    private fun drawPresence(
        drawList: ImDrawList,
        index: ReplayIndex,
        row: PlayerRow,
        y: Float,
        rowHeight: Float,
        left: Long,
        right: Long
    ) {
        val barTop = y + rowHeight * 0.5f - EditorFonts.px(3f)
        val barBottom = y + rowHeight * 0.5f + EditorFonts.px(3f)
        for (track in row.tracks) {
            val start = index.nanosOf(track.firstTick)
            val end = index.nanosOf(track.lastTick) + index.tickNanos
            if (end < left || start > right) continue
            val x0 = maxOf(geometry.originX - 2f, geometry.xAt(start))
            val x1 = minOf(geometry.originX + geometry.width + 2f, geometry.xAt(end))
            drawList.addRectFilled(x0, barTop, x1, barBottom, row.color.u32(0.28f), EditorFonts.px(3f))
            val columns = (x1 - x0).toInt()
            if (columns <= 0) continue
            var previousColor = 0
            var runStart = x0
            var x = x0
            while (x <= x1) {
                val tick = index.tickOf(geometry.nanosAt(x)).coerceIn(track.firstTick, track.lastTick)
                val health = track.health[track.index(tick)]
                val color = if (health.isNaN()) 0 else healthColor(health, 0.85f)
                if (color != previousColor) {
                    if (previousColor != 0) drawList.addRectFilled(
                        runStart,
                        barTop + EditorFonts.px(1.5f),
                        x,
                        barBottom - EditorFonts.px(1.5f),
                        previousColor
                    )
                    runStart = x
                    previousColor = color
                }
                x += 1f
            }
            if (previousColor != 0) drawList.addRectFilled(
                runStart,
                barTop + EditorFonts.px(1.5f),
                x1,
                barBottom - EditorFonts.px(1.5f),
                previousColor
            )
        }
    }

    private fun drawPlayerGlyphs(
        drawList: ImDrawList,
        index: ReplayIndex,
        row: PlayerRow,
        y: Float,
        rowHeight: Float,
        left: Long,
        right: Long,
        actions: Boolean,
    ) {
        val events = index.events
        val centerY = y + rowHeight / 2f
        val r = EditorFonts.px(4f)
        var useStartX = Float.NaN
        for (i in row.events) {
            val nanos = events.nanos[i]
            if (nanos < left) continue
            if (nanos > right) break
            val x = geometry.xAt(nanos)
            val kind = events.kindAt(i)
            val subject = events.a[i] in row.ids
            if (!actions) when (kind) {
                IndexEventKind.KILL -> if (subject) diamond(drawList, x, centerY, r + 1f, EditorTheme.EVENT_KILL.u32)
                IndexEventKind.DEATH -> if (subject) cross(drawList, x, centerY, r, EditorTheme.PURPLE.u32)
                IndexEventKind.HURT -> if (subject) drawList.addRectFilled(
                    x - 0.5f,
                    y + EditorFonts.px(2f),
                    x + 0.5f,
                    y + EditorFonts.px(6f),
                    EditorTheme.EVENT_HIT.u32(0.8f)
                )

                IndexEventKind.CRIT -> if (subject) drawList.addTriangleFilled(
                    x,
                    y + EditorFonts.px(2f),
                    x - 3f,
                    y + EditorFonts.px(7f),
                    x + 3f,
                    y + EditorFonts.px(7f),
                    EditorTheme.WARNING.u32
                )

                else -> Unit
            } else when (kind) {
                IndexEventKind.SWING -> drawList.addRectFilled(
                    x - 0.5f,
                    centerY - EditorFonts.px(2f),
                    x + 0.5f,
                    centerY + EditorFonts.px(2f),
                    EditorTheme.TEXT_DIM.u32(0.6f)
                )

                IndexEventKind.ATTACK -> drawList.addRectFilled(
                    x - 1f,
                    centerY - EditorFonts.px(4f),
                    x + 1f,
                    centerY + EditorFonts.px(4f),
                    EditorTheme.EVENT_KILL.u32(0.7f)
                )

                IndexEventKind.USE_START -> useStartX = x
                IndexEventKind.USE_END -> {
                    val start = if (useStartX.isNaN()) geometry.originX else useStartX
                    drawList.addRectFilled(
                        start,
                        y + rowHeight - EditorFonts.px(6f),
                        x,
                        y + rowHeight - EditorFonts.px(3f),
                        EditorTheme.SUCCESS.u32(0.7f)
                    )
                    useStartX = Float.NaN
                }

                IndexEventKind.EAT -> drawList.addCircleFilled(x, centerY, r - 1f, EditorTheme.SUCCESS.u32, 8)
                IndexEventKind.PICKUP -> drawList.addTriangleFilled(
                    x,
                    centerY - r,
                    x - r,
                    centerY + 1f,
                    x + r,
                    centerY + 1f,
                    EditorTheme.MINT.u32(0.8f)
                )

                IndexEventKind.EQUIP -> drawList.addRect(
                    x - 2f,
                    centerY - 2f,
                    x + 2f,
                    centerY + 2f,
                    EditorTheme.TEXT_MUTED.u32,
                    0f,
                    0,
                    1f
                )

                IndexEventKind.EFFECT -> drawList.addCircle(x, centerY, r, EditorTheme.PURPLE.u32, 8, 1.2f)
                else -> Unit
            }
        }
        if (!useStartX.isNaN()) drawList.addRectFilled(
            useStartX,
            y + rowHeight - EditorFonts.px(6f),
            geometry.originX + geometry.width,
            y + rowHeight - EditorFonts.px(3f),
            EditorTheme.SUCCESS.u32(0.7f)
        )
    }

    private fun drawHealth(drawList: ImDrawList, index: ReplayIndex, row: PlayerRow, y: Float, rowHeight: Float) {
        val pad = EditorFonts.px(2f)
        val bottom = y + rowHeight - pad
        val span = rowHeight - pad * 2f
        for (track in row.tracks) sampleColumns(index, track) { x, tick ->
            val health = track.health[track.index(tick)]
            if (health.isNaN()) return@sampleColumns
            val h = (health / 20f).coerceIn(0f, 1f) * span
            drawList.addRectFilled(x, bottom - h, x + 1f, bottom, healthColor(health, 0.55f))
        }
    }

    private fun drawSpeed(drawList: ImDrawList, index: ReplayIndex, row: PlayerRow, y: Float, rowHeight: Float) {
        val pad = EditorFonts.px(2f)
        val bottom = y + rowHeight - pad
        val span = rowHeight - pad * 2f
        val color = row.color.u32(0.7f)
        for (track in row.tracks) sampleColumns(index, track) { x, tick ->
            val speed = track.speedAt(tick, index.tickSeconds)
            val h = (speed / MAX_SPEED).coerceIn(0.0, 1.0).toFloat() * span
            if (h > 0.5f) drawList.addRectFilled(x, bottom - h, x + 1f, bottom, color)
        }
    }

    private inline fun sampleColumns(index: ReplayIndex, track: EntityTrack, draw: (x: Float, tick: Int) -> Unit) {
        val start = index.nanosOf(track.firstTick)
        val end = index.nanosOf(track.lastTick)
        val x0 = maxOf(geometry.originX, geometry.xAt(start))
        val x1 = minOf(geometry.originX + geometry.width, geometry.xAt(end))
        var x = x0
        while (x <= x1) {
            val tick = index.tickOf(geometry.nanosAt(x)).coerceIn(track.firstTick, track.lastTick)
            draw(x, tick)
            x += 1f
        }
    }

    // World lane

    fun drawWorld(drawList: ImDrawList, top: Float, rowHeight: Float) {
        val index = cachedIndex ?: return
        var y = top
        for (row in worldRows()) {
            when (row) {
                WorldRow.BLOCKS -> drawBlocks(drawList, index, y, rowHeight)
                WorldRow.EXPLOSIONS -> drawExplosions(drawList, index, y, rowHeight)
                WorldRow.PROJECTILES -> drawProjectiles(drawList, index, y, rowHeight)
                WorldRow.SOUNDS -> drawSounds(drawList, index, y, rowHeight)
                WorldRow.DIMENSION -> drawDimensions(drawList, y, rowHeight)
            }
            y += rowHeight
        }
    }

    private fun drawBlocks(drawList: ImDrawList, index: ReplayIndex, y: Float, rowHeight: Float) {
        val blocks = index.blocks
        if (blocks.size == 0) return
        val columns = geometry.width.toInt().coerceAtLeast(1)
        val counts = IntArray(columns)
        var i = blocks.lowerBound(geometry.leftNanos)
        val right = geometry.rightNanos
        var max = 1
        while (i < blocks.size && blocks.nanos[i] <= right) {
            val column = ((blocks.nanos[i] - geometry.leftNanos) / geometry.nanosPerPixel).toInt()
            if (column in 0 until columns) {
                counts[column]++
                if (counts[column] > max) max = counts[column]
            }
            i++
        }
        val bottom = y + rowHeight - EditorFonts.px(2f)
        val span = rowHeight - EditorFonts.px(4f)
        val color = EditorTheme.KEYFRAME_LINEAR.u32(0.55f)
        for (column in 0 until columns) {
            val count = counts[column]
            if (count == 0) continue
            val h = maxOf(EditorFonts.px(1.5f), span * (count.toFloat() / max))
            val x = geometry.originX + column
            drawList.addRectFilled(x, bottom - h, x + 1f, bottom, color)
        }
    }

    private fun drawExplosions(drawList: ImDrawList, index: ReplayIndex, y: Float, rowHeight: Float) {
        val events = index.events
        val centerY = y + rowHeight / 2f
        for (i in explosions) {
            val nanos = events.nanos[i]
            if (nanos < geometry.leftNanos - Nanos.PER_SECOND || nanos > geometry.rightNanos + Nanos.PER_SECOND) continue
            val x = geometry.xAt(nanos)
            val radius = (EditorFonts.px(2f) + events.value[i].coerceIn(
                0f,
                8f
            ) * EditorFonts.px(0.8f)).coerceAtMost(rowHeight / 2f - 1f)
            drawList.addCircleFilled(x, centerY, radius, EditorTheme.EVENT_HIT.u32(0.35f), 12)
            drawList.addCircle(x, centerY, radius, EditorTheme.EVENT_HIT.u32, 12, 1f)
        }
    }

    private fun drawProjectiles(drawList: ImDrawList, index: ReplayIndex, y: Float, rowHeight: Float) {
        val events = index.events
        val slots = 3
        val slotHeight = (rowHeight - EditorFonts.px(4f)) / slots
        var slot = 0
        for (i in projectiles) {
            val start = events.nanos[i]
            val end = projectileEnds[events.a[i]] ?: (start + Nanos.ofSeconds(2))
            if (end < geometry.leftNanos || start > geometry.rightNanos) continue
            val x0 = geometry.xAt(start)
            val x1 = maxOf(x0 + 2f, geometry.xAt(end))
            val top = y + EditorFonts.px(2f) + slot * slotHeight
            val color = shooterColor(events.b[i])
            drawList.addRectFilled(x0, top + 1f, x1, top + slotHeight - 1f, color.u32(0.75f), 2f)
            slot = (slot + 1) % slots
        }
    }

    private fun drawSounds(drawList: ImDrawList, index: ReplayIndex, y: Float, rowHeight: Float) {
        val events = index.events
        val color = EditorTheme.TEXT_DIM.u32(0.45f)
        val top = y + EditorFonts.px(5f)
        val bottom = y + rowHeight - EditorFonts.px(5f)
        for (i in sounds) {
            val nanos = events.nanos[i]
            if (nanos < geometry.leftNanos) continue
            if (nanos > geometry.rightNanos) break
            val x = geometry.xAt(nanos)
            drawList.addRectFilled(x, top, x + 1f, bottom, color)
        }
    }

    private fun drawDimensions(drawList: ImDrawList, y: Float, rowHeight: Float) {
        for ((start, end, dimension) in dimensionSegments) {
            if (end < geometry.leftNanos || start > geometry.rightNanos) continue
            val x0 = maxOf(geometry.originX, geometry.xAt(start))
            val x1 = minOf(geometry.originX + geometry.width, geometry.xAt(end))
            val color = when (dimension) {
                -1 -> EditorTheme.RECORD
                1 -> EditorTheme.PURPLE
                else -> EditorTheme.SUCCESS
            }
            drawList.addRectFilled(
                x0,
                y + EditorFonts.px(4f),
                x1,
                y + rowHeight - EditorFonts.px(4f),
                color.u32(0.3f),
                3f
            )
            EditorFonts.with(EditorFonts.small) {
                val label = dimensionName(dimension)
                if (x1 - x0 > Widgets.textWidth(label) + 8f) drawList.addText(
                    x0 + 5f,
                    y + (rowHeight - ImGui.getFontSize()) / 2f,
                    EditorTheme.TEXT_MUTED.u32,
                    label
                )
            }
        }
    }

    // Moments lane

    fun moments(session: EditorSession): List<Moment> = MomentActions.all(session)

    fun drawMoments(drawList: ImDrawList, session: EditorSession, top: Float, height: Float) {
        val selection = session.selection.momentIds
        val pad = EditorFonts.px(4f)
        for (moment in moments(session)) {
            if (moment.endNanos < geometry.leftNanos || moment.nanos > geometry.rightNanos) continue
            val x0 = geometry.xAt(moment.nanos)
            val x1 = maxOf(x0 + 3f, geometry.xAt(moment.endNanos))
            val kept = moment.origin == MomentOrigin.MANUAL || session.project.moment(moment.id) != null
            val color = EditorTheme.Rgb(moment.kind.color)
            val alpha = if (kept) 0.85f else (0.25f + 0.25f * moment.score.toFloat())
            drawList.addRectFilled(x0, top + pad, x1, top + height - pad, color.u32(alpha), EditorFonts.px(4f))
            if (!kept) drawList.addRect(
                x0,
                top + pad,
                x1,
                top + height - pad,
                color.u32(0.7f),
                EditorFonts.px(4f),
                0,
                1f
            )
            val peakX = geometry.xAt(moment.peakNanos)
            drawList.addRectFilled(
                peakX - 1f,
                top + pad,
                peakX + 1f,
                top + height - pad,
                EditorTheme.TEXT.u32(if (kept) 0.9f else 0.5f)
            )
            if (moment.id in selection) drawList.addRect(
                x0 - 2f,
                top + pad - 2f,
                x1 + 2f,
                top + height - pad + 2f,
                EditorTheme.SELECTION.u32,
                EditorFonts.px(5f),
                0,
                2f
            )
            EditorFonts.with(EditorFonts.small) {
                val available = x1 - x0 - 10f
                if (available > 20f) drawList.addText(
                    x0 + 5f, top + (height - ImGui.getFontSize()) / 2f,
                    if (kept) EditorTheme.TEXT.u32 else EditorTheme.TEXT_MUTED.u32,
                    Widgets.clip(moment.label, available)
                )
            }
        }
    }

    fun momentAt(session: EditorSession, mouseX: Float): Moment? {
        val nanos = geometry.nanosAt(mouseX)
        val grab = (geometry.nanosPerPixel * 3).toLong()
        return moments(session).lastOrNull { nanos >= it.nanos - grab && nanos <= it.endNanos + grab }
    }

    // Hover, click, menus

    fun hoverPlayers(session: EditorSession, mouseX: Float, offsetY: Float, rowHeight: Float) {
        val index = cachedIndex ?: return
        val (row, sub) = playerRowAt(offsetY, rowHeight) ?: return
        val nanos = geometry.nanosAt(mouseX)
        val tick = index.tickOf(nanos)
        val track = row.tracks.firstOrNull { it.covers(tick) }
        val nearest = nearestPlayerEvent(index, row, nanos, sub == SubRow.ACTIONS)
        ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
        TimelineTooltip.show {
            TimelineTooltip.title(Icon.PERSON, row.color, row.name, TimeFormat.clock(nanos))
            if (track == null) TimelineTooltip.text("Not in view") else playerDetails(index, track, tick)
            if (nearest >= 0) {
                val delta = (nanos - index.events.nanos[nearest]) / 1_000_000_000.0
                val moment = when {
                    abs(delta) < 0.05 -> "now"
                    delta > 0 -> String.format("%.1f s ago", delta)
                    else -> String.format("in %.1f s", -delta)
                }
                TimelineTooltip.text("${labels?.label(nearest) ?: ""}   $moment")
            }
            TimelineTooltip.hints(listOf("Click to jump", "Double-click to expand"))
        }
    }

    private fun playerDetails(index: ReplayIndex, track: EntityTrack, tick: Int) {
        val i = track.index(tick)
        val health = track.health[i]
        val chips = ArrayList<String>()
        chips += if (health.isNaN()) "Health unknown" else String.format("%.1f hearts", health / 2f)
        val held = track.held[i].toInt()
        if (held >= 0) chips += Items.label(held)
        if (track.flag(tick, EntityTrack.FLAG_SPRINTING)) chips += "Sprinting"
        if (track.flag(tick, EntityTrack.FLAG_SNEAKING)) chips += "Sneaking"
        if (track.flag(tick, EntityTrack.FLAG_USING)) chips += "Using item"
        if (track.flag(tick, EntityTrack.FLAG_ON_FIRE)) chips += "Burning"
        if (!track.flag(tick, EntityTrack.FLAG_ON_GROUND)) chips += "Airborne"
        if (track.vehicle[i] >= 0) chips += "Riding"
        TimelineTooltip.chips(chips)
        val near = index.players.count {
            it !== track && it.entityId != track.entityId && it.covers(tick) && distance(track, it, tick) <= 10.0
        }
        val rows = ArrayList<TimelineTooltip.Row>()
        rows += TimelineTooltip.Row("Speed", listOf("" to String.format("%.1f m/s", track.speedAt(tick, index.tickSeconds))))
        lookingAt(index, track, tick)?.let { rows += TimelineTooltip.Row("Looking at", listOf("" to it)) }
        rows += TimelineTooltip.Row("Nearby", listOf("" to "$near player${if (near == 1) "" else "s"} within 10 m"))
        rows += TimelineTooltip.Row(
            "Position",
            listOf("X" to String.format("%.1f", track.x[i]), "Y" to String.format("%.1f", track.y[i]), "Z" to String.format("%.1f", track.z[i]))
        )
        TimelineTooltip.grid(rows)
    }

    private fun nearestPlayerEvent(index: ReplayIndex, row: PlayerRow, nanos: Long, actions: Boolean): Int {
        val events = index.events
        var best = -1
        var bestDelta = Nanos.ofSeconds(3)
        for (i in row.events) {
            val kind = events.kindAt(i)
            val isAction =
                kind == IndexEventKind.SWING || kind == IndexEventKind.USE_START || kind == IndexEventKind.USE_END ||
                        kind == IndexEventKind.EAT || kind == IndexEventKind.PICKUP || kind == IndexEventKind.EQUIP || kind == IndexEventKind.EFFECT || kind == IndexEventKind.ATTACK
            if (isAction != actions) continue
            val delta = abs(events.nanos[i] - nanos)
            if (delta < bestDelta) {
                bestDelta = delta
                best = i
            }
        }
        return best
    }

    fun clickPlayers(session: EditorSession, mouseX: Float, offsetY: Float, rowHeight: Float): Boolean {
        val index = cachedIndex ?: return false
        val (row, sub) = playerRowAt(offsetY, rowHeight) ?: return false
        val replay = session.replay ?: return false
        val nanos = geometry.nanosAt(mouseX)
        val nearest = nearestPlayerEvent(index, row, nanos, sub == SubRow.ACTIONS)
        val grab = (geometry.nanosPerPixel * 6).toLong()
        val target =
            if (nearest >= 0 && abs(index.events.nanos[nearest] - nanos) <= grab) index.events.nanos[nearest] else nanos
        replay.seek(target.coerceIn(replay.startNanos, replay.endNanos))
        val track = row.tracks.firstOrNull { it.covers(index.tickOf(target)) }
        if (track != null) context.selectEntity(track.entityId, row.name, true, row.isRecorder, track.uuid?.toString())
        return true
    }

    fun hoverWorld(mouseX: Float, offsetY: Float, rowHeight: Float) {
        val index = cachedIndex ?: return
        val rows = worldRows()
        val rowIndex = (offsetY / rowHeight).toInt()
        val row = rows.getOrNull(rowIndex) ?: return
        val nanos = geometry.nanosAt(mouseX)
        val grab = (geometry.nanosPerPixel * 6).toLong()
        when (row) {
            WorldRow.BLOCKS -> {
                val blocks = index.blocks
                var i = blocks.lowerBound(nanos - grab)
                var count = 0
                var first = -1
                while (i < blocks.size && blocks.nanos[i] <= nanos + grab) {
                    if (first < 0) first = i
                    count++
                    i++
                }
                if (count == 1) TimelineTooltip.simple(row.icon, EditorTheme.KEYFRAME_LINEAR, labels?.blockLabel(first) ?: "Block change", TimeFormat.clock(blocks.nanos[first]))
                else if (count > 1) TimelineTooltip.simple(row.icon, EditorTheme.KEYFRAME_LINEAR, "$count block changes", "around ${TimeFormat.clock(nanos)}")
            }

            WorldRow.EXPLOSIONS -> nearestOf(index, explosions, nanos, grab * 3)?.let { tooltipEvent(index, row, it) }
            WorldRow.PROJECTILES -> nearestOf(index, projectiles, nanos, Nanos.ofSeconds(2))?.let { tooltipEvent(index, row, it) }
            WorldRow.SOUNDS -> nearestOf(index, sounds, nanos, grab)?.let { tooltipEvent(index, row, it) }
            WorldRow.DIMENSION -> dimensionSegments.firstOrNull { nanos in it.first..it.second }?.let {
                TimelineTooltip.simple(row.icon, EditorTheme.SUCCESS, dimensionName(it.third), "${TimeFormat.clock(it.first)} to ${TimeFormat.clock(it.second)}")
            }
        }
    }

    fun clickWorld(session: EditorSession, mouseX: Float, offsetY: Float, rowHeight: Float): Boolean {
        val index = cachedIndex ?: return false
        val replay = session.replay ?: return false
        val row = worldRows().getOrNull((offsetY / rowHeight).toInt()) ?: return false
        val nanos = geometry.nanosAt(mouseX)
        val grab = (geometry.nanosPerPixel * 6).toLong()
        val hit = when (row) {
            WorldRow.EXPLOSIONS -> nearestOf(index, explosions, nanos, grab * 3)
            WorldRow.PROJECTILES -> nearestOf(index, projectiles, nanos, Nanos.ofSeconds(2))
            WorldRow.SOUNDS -> nearestOf(index, sounds, nanos, grab)
            else -> null
        }
        val target = if (hit != null) index.events.nanos[hit] else nanos
        replay.seek(target.coerceIn(replay.startNanos, replay.endNanos))
        if (hit != null) context.status(labels?.label(hit) ?: "")
        return true
    }

    fun hoverMoment(moment: Moment, kept: Boolean) {
        ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
        TimelineTooltip.show {
            TimelineTooltip.title(Icon.BOOKMARK, EditorTheme.Rgb(moment.kind.color), moment.label, "${TimeFormat.clock(moment.nanos)} to ${TimeFormat.clock(moment.endNanos)}")
            TimelineTooltip.chips(listOf(moment.kind.label, "Score ${(moment.score * 100).toInt()}", if (kept) "Kept" else "Detected"))
            TimelineTooltip.hints(listOf("Click to select", "Double-click to play", "Right-click for actions"))
        }
    }

    fun momentMenu(session: EditorSession, moment: Moment) = MomentActions.menu(context, session, moment, renameBuffer)

    fun beginMomentMenu(moment: Moment) = renameBuffer.set(moment.label)

    fun momentsLaneMenu(session: EditorSession) {
        val detected = session.events.moments.filter { session.project.moment(it.id) == null }
        val all = moments(session)
        if (Menus.item("Add moment at playhead")) addManual(session, session.playheadNanos)
        if (Menus.item("Keep all detected moments", "", false, detected.isNotEmpty())) {
            for (moment in detected) keep(session, moment)
            context.toast("Kept ${detected.size} moments")
        }
        if (Menus.item("Clips from all moments", "", false, all.isNotEmpty())) {
            for (moment in all) clipFrom(session, moment)
            context.toast("Added ${all.size} clips")
            context.openPanel("Clips")
        }
        if (Menus.item(
                "Select all kept moments",
                "",
                false,
                session.project.moments.isNotEmpty()
            )
        ) session.selection =
            Selection(momentIds = session.project.moments.map { it.id }.toSet())
        ImGui.separator()
        Widgets.smallText("Detected moments are outlined; keep the ones you want.", EditorTheme.TEXT_DIM.u32)
    }

    fun playersLaneMenu() {
        val anyExpanded = rows.any { it.expanded }
        if (Menus.item(if (anyExpanded) "Collapse all" else "Expand all")) for (row in rows) row.expanded =
            !anyExpanded
        if (rows.size > MAX_ROWS && Menus.item(if (showAllPlayers) "Show the ${MAX_ROWS} most present" else "Show all ${rows.size} players")) showAllPlayers =
            !showAllPlayers
        ImGui.separator()
        Widgets.smallText(
            "One row per player: presence, health, kills and deaths. Expand for health, speed and actions.",
            EditorTheme.TEXT_DIM.u32
        )
    }

    fun worldLaneMenu() {
        for (row in WorldRow.entries) {
            if (row == WorldRow.DIMENSION && dimensionSegments.size <= 1) continue
            if (Menus.item(row.label, "", row !in hiddenWorldRows)) {
                if (!hiddenWorldRows.remove(row)) hiddenWorldRows.add(row)
            }
        }
    }

    fun addManual(session: EditorSession, nanos: Long) = MomentActions.addManual(session, nanos)

    fun keep(session: EditorSession, moment: Moment) = MomentActions.keep(session, moment)

    fun clipFrom(session: EditorSession, moment: Moment) = MomentActions.clipFrom(session, moment)

    // Helpers

    private fun nearestOf(index: ReplayIndex, list: IntArray, nanos: Long, window: Long): Int? {
        var best = -1
        var bestDelta = window
        for (i in list) {
            val delta = abs(index.events.nanos[i] - nanos)
            if (delta <= bestDelta) {
                bestDelta = delta
                best = i
            }
        }
        return if (best >= 0) best else null
    }

    private fun tooltipEvent(index: ReplayIndex, row: WorldRow, i: Int) {
        ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
        TimelineTooltip.show {
            TimelineTooltip.title(row.icon, EditorTheme.EVENT_HIT, labels?.label(i) ?: row.label, TimeFormat.clock(index.events.nanos[i]))
            if (index.events.hasPosition(i)) TimelineTooltip.grid(
                listOf(
                    TimelineTooltip.Row(
                        "Position",
                        listOf(
                            "X" to String.format("%.1f", index.events.x[i]),
                            "Y" to String.format("%.1f", index.events.y[i]),
                            "Z" to String.format("%.1f", index.events.z[i])
                        )
                    )
                )
            )
            TimelineTooltip.hints(listOf("Click to jump"))
        }
    }

    private fun lookingAt(index: ReplayIndex, from: EntityTrack, tick: Int): String? {
        val i = from.index(tick)
        val yaw = Math.toRadians(from.headYaw[i].toDouble())
        val pitch = Math.toRadians(from.pitch[i].toDouble())
        val fx = -Math.sin(yaw) * Math.cos(pitch)
        val fy = -Math.sin(pitch)
        val fz = Math.cos(yaw) * Math.cos(pitch)
        var best: EntityTrack? = null
        var bestDistance = 48.0
        for (other in index.players) {
            if (other.entityId == from.entityId || !other.covers(tick)) continue
            val j = other.index(tick)
            val dx = other.x[j] - from.x[i]
            val dy = other.y[j] + 0.9 - (from.y[i] + 1.62)
            val dz = other.z[j] - from.z[i]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < 1e-6 || d > bestDistance) continue
            val dot = (fx * dx + fy * dy + fz * dz) / d
            val halfWidth = (0.6 / d).coerceAtMost(0.9)
            if (dot >= sqrt(1.0 - halfWidth * halfWidth) - 0.03) {
                best = other
                bestDistance = d
            }
        }
        return best?.label
    }

    private fun distance(a: EntityTrack, b: EntityTrack, tick: Int): Double {
        val i = a.index(tick)
        val j = b.index(tick)
        val dx = a.x[i] - b.x[j]
        val dy = a.y[i] - b.y[j]
        val dz = a.z[i] - b.z[j]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun shooterColor(shooter: Int): EditorTheme.Rgb =
        rows.firstOrNull { shooter in it.ids }?.color ?: EditorTheme.TEXT_MUTED

    private fun healthColor(health: Float, alpha: Float): Int {
        val t = (health / 20f).coerceIn(0f, 1f)
        val r: Float
        val g: Float
        if (t < 0.5f) {
            r = 1f
            g = t * 2f
        } else {
            r = 1f - (t - 0.5f) * 2f
            g = 1f
        }
        return ImGui.getColorU32(r * 0.9f, g * 0.85f, 0.25f, alpha)
    }

    private fun diamond(drawList: ImDrawList, x: Float, y: Float, r: Float, color: Int) {
        drawList.addQuadFilled(x, y - r, x + r, y, x, y + r, x - r, y, color)
    }

    private fun cross(drawList: ImDrawList, x: Float, y: Float, r: Float, color: Int) {
        drawList.addLine(x - r, y - r, x + r, y + r, color, 2f)
        drawList.addLine(x - r, y + r, x + r, y - r, color, 2f)
    }

    private fun dimensionName(dimension: Int): String = when (dimension) {
        -1 -> "Nether"
        0 -> "Overworld"
        1 -> "End"
        else -> "Dimension $dimension"
    }

    companion object {
        const val MAX_ROWS = 6
        const val MIN_PRESENCE_TICKS = 40L
        const val MAX_SPEED = 12.0
    }
}
