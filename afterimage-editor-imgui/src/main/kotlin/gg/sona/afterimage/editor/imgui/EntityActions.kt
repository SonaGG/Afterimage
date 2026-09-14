package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.camera.CameraMode
import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.camera.CameraSettings
import gg.sona.afterimage.camera.Rotation
import gg.sona.afterimage.editor.ViewState
import gg.sona.afterimage.editor.commands.SetViewKeyframe
import imgui.ImGui
import org.joml.Vector3d
import kotlin.math.atan2
import kotlin.math.sqrt

object EntityActions {
    fun targetId(entity: EntityRef): Int = if (entity.isRecorder) CameraSettings.TARGET_RECORDER else entity.id

    fun isTargeted(settings: CameraSettings, entity: EntityRef): Boolean =
        if (entity.isRecorder) settings.targetsRecorder() else settings.targetEntityId == entity.id

    fun target(context: EditorContext, entity: EntityRef) {
        val control = context.host.camera
        control.settings.targetEntityId = targetId(entity)
        if (control.settings.mode != CameraMode.FREE) control.apply()
    }

    fun setMode(context: EditorContext, mode: CameraMode, entity: EntityRef) {
        val control = context.host.camera
        control.settings.targetEntityId = targetId(entity)
        control.settings.mode = mode
        control.apply()
        context.status("${mode.label}: ${entity.name}")
    }

    fun flyTo(context: EditorContext, entity: EntityRef) {
        context.selectedEntityId = entity.id
        context.host.camera.frame(entity.x, entity.y + 1.0, entity.z, 2.0)
    }

    fun lookAt(context: EditorContext, entity: EntityRef) {
        val control = context.host.camera
        val current = control.currentPose()
        if (control.settings.mode != CameraMode.FREE) {
            control.settings.mode = CameraMode.FREE
            control.apply()
        }
        control.teleport(
            CameraPose(
                Vector3d(current.position),
                lookRotation(current.position, Vector3d(entity.x, entity.y + 1.0, entity.z)),
                current.fov
            )
        )
    }

    fun toggleHidden(context: EditorContext, entity: EntityRef) {
        val hidden = context.visuals.hiddenEntities
        if (!hidden.remove(entity.id)) hidden += entity.id
    }

    fun isHidden(context: EditorContext, entity: EntityRef): Boolean = context.visuals.isHidden(entity.id)

    fun toggleHiddenDuringExport(context: EditorContext, entity: EntityRef) {
        val hidden = context.visuals.hiddenDuringExport
        if (!hidden.remove(entity.id)) hidden += entity.id
    }

    fun isHiddenDuringExport(context: EditorContext, entity: EntityRef): Boolean =
        entity.id in context.visuals.hiddenDuringExport

    fun toggleNametag(context: EditorContext, entity: EntityRef) {
        val visuals = context.visuals
        val current = visuals.overrideFor(entity.id)
        visuals.setOverride(entity.id, current.copy(hideNametag = !current.hideNametag))
    }

    fun nametagHidden(context: EditorContext, entity: EntityRef): Boolean =
        context.visuals.overrideFor(entity.id).hideNametag

    fun viewKeyframe(context: EditorContext, mode: CameraMode, entity: EntityRef) {
        val session = context.session ?: return
        val settings = context.host.camera.settings
        val snapshot = ViewState.capture(settings).copy(mode = mode, targetEntityId = targetId(entity))
        session.execute(SetViewKeyframe(session.playheadNanos, snapshot))
        context.status("View keyframe: ${mode.label} ${entity.name} at ${TimeFormat.clock(session.playheadNanos)}")
    }

    fun menu(context: EditorContext, entity: EntityRef) {
        val settings = context.host.camera.settings
        val hidden = isHidden(context, entity)
        EditorFonts.with(EditorFonts.bodyMedium) { ImGui.textUnformatted(entity.name) }
        Widgets.smallText(
            if (entity.isRecorder) "Recorder" else if (entity.isPlayer) "Player" else "Entity #${entity.id}",
            EditorTheme.TEXT_DIM.u32
        )
        ImGui.separator()
        if (Menus.item(
                "First person",
                "",
                isTargeted(settings, entity) && settings.mode == CameraMode.FIRST_PERSON
            )
        ) setMode(context, CameraMode.FIRST_PERSON, entity)
        if (Menus.item("Orbit", "", isTargeted(settings, entity) && settings.mode == CameraMode.ORBIT)) setMode(
            context,
            CameraMode.ORBIT,
            entity
        )
        if (Menus.item("Follow", "", isTargeted(settings, entity) && settings.mode == CameraMode.FOLLOW)) setMode(
            context,
            CameraMode.FOLLOW,
            entity
        )
        if (Menus.item("Chase", "", isTargeted(settings, entity) && settings.mode == CameraMode.CHASE)) setMode(
            context,
            CameraMode.CHASE,
            entity
        )
        ImGui.separator()
        if (Menus.item("Fly to")) flyTo(context, entity)
        if (Menus.item("Look at")) lookAt(context, entity)
        if (context.session != null && ImGui.beginMenu("Add view keyframe")) {
            for (mode in listOf(CameraMode.FIRST_PERSON, CameraMode.ORBIT, CameraMode.FOLLOW, CameraMode.CHASE)) {
                if (Menus.item(mode.label)) viewKeyframe(context, mode, entity)
            }
            ImGui.endMenu()
        }
        ImGui.separator()
        if (!entity.isRecorder && Menus.item(if (hidden) "Show entity" else "Hide entity")) toggleHidden(
            context,
            entity
        )
        if (!entity.isRecorder && ImGui.beginMenu("More")) {
            if (Menus.item("Hide nametag", "", nametagHidden(context, entity))) toggleNametag(context, entity)
            if (Menus.item(
                    "Hide during export only",
                    "",
                    isHiddenDuringExport(context, entity)
                )
            ) toggleHiddenDuringExport(context, entity)
            ImGui.endMenu()
        }
        if (entity.uuid != null && Menus.item("Copy UUID")) ImGui.setClipboardText(entity.uuid)
    }

    fun lookRotation(from: Vector3d, to: Vector3d): Rotation {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val horizontal = sqrt(dx * dx + dz * dz)
        val yaw = Math.toDegrees(atan2(-dx, dz))
        val pitch = -Math.toDegrees(atan2(dy, horizontal))
        return Rotation(yaw, pitch)
    }
}
