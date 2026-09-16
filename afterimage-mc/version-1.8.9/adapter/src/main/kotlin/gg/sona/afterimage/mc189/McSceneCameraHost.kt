package gg.sona.afterimage.mc189

import gg.sona.afterimage.mc189.replay.ViewportPicker
import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.camera.Rotation
import gg.sona.afterimage.mc.common.SceneCameraHost
import net.minecraft.client.Minecraft
import net.minecraft.util.math.Vec3d
import org.joml.Vector3d

class McSceneCameraHost(private val minecraft: Minecraft, private val picker: ViewportPicker, private val roll: () -> Double) : SceneCameraHost {
    override fun playerEyePose(): CameraPose? {
        val player = minecraft.player ?: return null
        return CameraPose(Vector3d(player.x, player.y + player.getEyeHeight(), player.z), Rotation(player.yaw.toDouble(), player.pitch.toDouble(), roll()), CameraPose.DEFAULT_FOV)
    }

    override fun applyPose(pose: CameraPose) {
        val player = minecraft.player ?: return
        player.setPosition(pose.position.x, pose.position.y - player.getEyeHeight(), pose.position.z)
        player.prevX = player.x
        player.prevY = player.y
        player.prevZ = player.z
        player.lastX = player.x
        player.lastY = player.y
        player.lastZ = player.z
        player.yaw = pose.rotation.yaw.toFloat()
        player.pitch = pose.rotation.pitch.toFloat()
        player.lastYaw = player.yaw
        player.lastPitch = player.pitch
    }

    override fun rayHitDistance(origin: Vector3d, direction: Vector3d, maxDistance: Double): Double? {
        val world = minecraft.world ?: return null
        val end = Vector3d(origin).add(Vector3d(direction).mul(maxDistance))
        val hit = world.rayTrace(Vec3d(origin.x, origin.y, origin.z), Vec3d(end.x, end.y, end.z), false, true, false) ?: return null
        val point = hit.facePos ?: return null
        val distance = Vector3d(point.x, point.y, point.z).distance(origin)
        return if (distance > 0.05) distance else null
    }

    override fun ray(normalizedX: Float, normalizedY: Float): Vector3d? = picker.ray(normalizedX, normalizedY)
}
