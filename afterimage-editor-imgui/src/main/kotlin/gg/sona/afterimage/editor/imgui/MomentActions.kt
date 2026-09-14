package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.*
import gg.sona.afterimage.editor.commands.*
import imgui.ImGui
import imgui.flag.ImGuiInputTextFlags
import imgui.type.ImString
import java.util.*

object MomentActions {
    fun all(session: EditorSession): List<Moment> {
        val kept = session.project.moments
        val keptIds = kept.map { it.id }.toSet()
        return (kept + session.events.moments.filter { it.id !in keptIds }).sortedBy { it.nanos }
    }

    fun isKept(session: EditorSession, moment: Moment): Boolean = session.project.moment(moment.id) != null

    fun play(session: EditorSession, moment: Moment) {
        val replay = session.replay ?: return
        session.execute(SetInOutPoints(moment.nanos, moment.endNanos))
        replay.seek(moment.nanos)
        replay.play()
        session.selection = Selection(momentIds = setOf(moment.id))
    }

    fun keep(session: EditorSession, moment: Moment) {
        if (isKept(session, moment)) return
        session.execute(AddMoment(moment))
    }

    fun clipFrom(session: EditorSession, moment: Moment) {
        val project = session.project
        session.execute(
            AddClip(
                Clip(
                    UUID.randomUUID(),
                    project.recording,
                    project.sessionId,
                    moment.nanos,
                    moment.endNanos,
                    moment.label
                )
            )
        )
    }

    fun addManual(session: EditorSession, nanos: Long) {
        val replay = session.replay ?: return
        val moment = Moment(
            UUID.randomUUID(),
            maxOf(replay.startNanos, nanos - Nanos.ofSeconds(4)),
            minOf(replay.endNanos, nanos + Nanos.ofSeconds(3)),
            nanos,
            "Moment ${session.project.moments.size + 1}",
            MomentKind.CUSTOM,
            0.5,
            MomentOrigin.MANUAL,
            -1
        )
        session.execute(AddMoment(moment))
        session.selection = Selection(momentIds = setOf(moment.id))
    }

    fun menu(context: EditorContext, session: EditorSession, moment: Moment, renameBuffer: ImString) {
        val replay = session.replay ?: return
        val kept = isKept(session, moment)
        Widgets.mutedText("${moment.kind.label}   ${TimeFormat.clock(moment.nanos)}")
        ImGui.separator()
        if (kept) {
            ImGui.setNextItemWidth(200f)
            if (ImGui.inputText("##rename", renameBuffer, ImGuiInputTextFlags.EnterReturnsTrue)) {
                session.execute(ReplaceMoment(moment.id, moment.copy(label = renameBuffer.get())))
                ImGui.closeCurrentPopup()
            }
            if (ImGui.beginMenu("Kind")) {
                for (kind in MomentKind.entries) if (Menus.item(
                        kind.label,
                        "",
                        moment.kind == kind
                    )
                ) session.execute(
                    ReplaceMoment(moment.id, moment.copy(kind = kind))
                )
                ImGui.endMenu()
            }
            ImGui.separator()
        }
        if (Menus.item("Play moment")) play(session, moment)
        if (Menus.item("Go to peak")) replay.seek(moment.peakNanos)
        if (Menus.item("Set in/out to moment")) session.execute(SetInOutPoints(moment.nanos, moment.endNanos))
        ImGui.separator()
        if (!kept && Menus.item("Keep moment")) keep(session, moment)
        if (Menus.item("Clip from moment")) clipFrom(session, moment)
        if (Menus.item("Add marker at peak")) session.execute(
            AddMarker(
                TimelineMarker(
                    UUID.randomUUID(),
                    moment.peakNanos,
                    moment.label,
                    moment.kind.color,
                    MarkerKind.MOMENT
                )
            )
        )
        if (moment.entityId >= 0 && Menus.item("Search this player")) {
            val index = session.events.index
            val name = index?.nameOf(moment.entityId, index.tickOf(moment.peakNanos))
            if (name != null) {
                context.searchRequest = "event player:$name"
                context.openPanel("Search")
            }
        }
        if (kept) {
            ImGui.separator()
            if (Menus.item("Remove moment", "Del")) {
                session.execute(RemoveMoment(moment.id))
                session.selection = Selection.NONE
            }
        }
    }
}
