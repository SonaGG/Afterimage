package gg.sona.recast.editor.imgui

import gg.sona.recast.camera.Easing
import gg.sona.recast.camera.track.SegmentMode
import gg.sona.recast.editor.EditorSession
import gg.sona.recast.editor.commands.RemovePoseKeyframes
import gg.sona.recast.editor.commands.SetPoseKeyframe
import gg.sona.recast.editor.pose.BodyPart
import gg.sona.recast.editor.pose.BodyPose
import gg.sona.recast.editor.pose.PartPose
import gg.sona.recast.replay.state.shadow.EntityKind

object PoseTools {
    private val HUMANOID_MOBS = setOf(51, 53, 54, 57, 58)

    fun poseable(session: EditorSession, entityId: Int): Boolean {
        val shadow = session.replay?.shadow ?: return false
        if (entityId == shadow.localPlayer.entityId) return true
        val entity = shadow.entities[entityId] ?: return false
        return entity.isPlayer || (entity.kind == EntityKind.MOB && entity.type in HUMANOID_MOBS)
    }

    fun currentPose(session: EditorSession, entityId: Int): BodyPose {
        val track = session.project.poses[entityId] ?: return BodyPose.EMPTY
        if (track.isEmpty) return BodyPose.EMPTY
        return track.valueAt(session.playheadNanos) ?: BodyPose.EMPTY
    }

    fun keyframeCount(session: EditorSession, entityId: Int): Int =
        session.project.poses[entityId]?.keyframes?.size ?: 0

    fun hasKeyframeAtPlayhead(session: EditorSession, entityId: Int): Boolean =
        session.project.poses[entityId]?.at(session.playheadNanos) != null

    fun setPart(session: EditorSession, entityId: Int, part: BodyPart, pose: PartPose) {
        val time = session.playheadNanos
        val existing = session.project.poses[entityId]?.at(time)
        val base = existing?.value ?: currentPose(session, entityId)
        session.execute(
            SetPoseKeyframe(
                entityId,
                time,
                base.with(part, pose),
                existing?.easing ?: Easing.LINEAR,
                existing?.mode ?: SegmentMode.LINEAR
            )
        )
    }

    fun releasePart(session: EditorSession, entityId: Int, part: BodyPart) {
        val time = session.playheadNanos
        val existing = session.project.poses[entityId]?.at(time)
        val base = existing?.value ?: currentPose(session, entityId)
        if (base[part] == null) return
        session.execute(
            SetPoseKeyframe(
                entityId,
                time,
                base.without(part),
                existing?.easing ?: Easing.LINEAR,
                existing?.mode ?: SegmentMode.LINEAR
            )
        )
    }

    fun keyHere(session: EditorSession, entityId: Int) {
        val time = session.playheadNanos
        val existing = session.project.poses[entityId]?.at(time)
        if (existing != null) return
        session.execute(SetPoseKeyframe(entityId, time, currentPose(session, entityId)))
    }

    fun releaseHere(session: EditorSession, entityId: Int) {
        val time = session.playheadNanos
        val existing = session.project.poses[entityId]?.at(time)
        session.execute(
            SetPoseKeyframe(
                entityId,
                time,
                BodyPose.EMPTY,
                existing?.easing ?: Easing.LINEAR,
                existing?.mode ?: SegmentMode.LINEAR
            )
        )
    }

    fun clearAll(session: EditorSession, entityId: Int) {
        val track = session.project.poses[entityId] ?: return
        session.execute(RemovePoseKeyframes(entityId, track.keyframes.map { it.timeNanos }.toSet()))
    }

    fun wrap(degrees: Double): Double {
        var value = degrees % 360.0
        if (value > 180.0) value -= 360.0
        if (value < -180.0) value += 360.0
        return value
    }
}
