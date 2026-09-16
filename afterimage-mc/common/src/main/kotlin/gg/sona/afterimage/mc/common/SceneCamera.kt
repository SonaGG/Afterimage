package gg.sona.afterimage.mc.common

import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.camera.CameraSettings
import gg.sona.afterimage.camera.Rotation
import gg.sona.afterimage.core.time.Nanos
import org.joml.Vector3d
import org.lwjgl.sdl.SDLMouse
import org.lwjgl.sdl.SDLScancode

interface SceneCameraHost {
    fun playerEyePose(): CameraPose?
    fun applyPose(pose: CameraPose)
    fun rayHitDistance(origin: Vector3d, direction: Vector3d, maxDistance: Double): Double?
    fun ray(normalizedX: Float, normalizedY: Float): Vector3d?
}

class SceneCamera(
    private val host: SceneCameraHost,
    private val settings: CameraSettings,
) {

    enum class Gesture { NONE, FLY, ORBIT, PAN, DOLLY }

    private class Flight(val from: CameraPose, val to: CameraPose, val durationNanos: Long) {
        var elapsedNanos = 0L
    }

    var gesture: Gesture = Gesture.NONE
        private set
    var viewport: () -> IntArray? = { null }
    var lockCursor: (Double, Double) -> Unit = { _, _ -> }
    var unlockCursor: () -> Unit = {}
    var speedFlashUntilNanos: Long = 0L
        private set
    private var gestureTravel = 0.0
    private var gestureEndNanos = 0L

    fun recentGestureTravel(): Double =
        if (System.nanoTime() - gestureEndNanos < Nanos.ofMillis(400)) gestureTravel else 0.0

    private val velocity = Vector3d()
    private val pivot = Vector3d()
    private var pivotDistance = 8.0
    private var pendingScroll = 0f
    private var pressCursorX = 0.0
    private var pressCursorY = 0.0
    private var holdSeconds = 0.0
    private var flight: Flight? = null
    private var yaw = 0.0
    private var pitch = 0.0
    private val cursor = DoubleArray(2)
    private val motion = DoubleArray(2)

    fun scroll(amount: Float) {
        pendingScroll += amount
    }

    fun resetMotion() {
        velocity.set(0.0, 0.0, 0.0)
        holdSeconds = 0.0
        flight = null
    }

    fun cancelGesture() {
        if (gesture != Gesture.NONE) unlockCursor()
        gesture = Gesture.NONE
        velocity.set(0.0, 0.0, 0.0)
        holdSeconds = 0.0
    }

    fun frame(target: Vector3d, radius: Double, nowPose: CameraPose) {
        val forward = nowPose.rotation.forward()
        val distance = maxOf(1.5, radius * 2.4)
        val eye = Vector3d(target).sub(Vector3d(forward).mul(distance))
        pivot.set(target)
        pivotDistance = distance
        flight = Flight(nowPose, CameraPose(eye, nowPose.rotation, nowPose.fov), Nanos.ofMillis(320))
        velocity.set(0.0, 0.0, 0.0)
    }

    fun snapView(rotation: Rotation, nowPose: CameraPose) {
        val distance = pivotDistance.coerceIn(2.0, 64.0)
        val target = Vector3d(nowPose.position).add(Vector3d(nowPose.rotation.forward()).mul(distance))
        val eye = Vector3d(target).sub(Vector3d(rotation.forward()).mul(distance))
        pivot.set(target)
        flight = Flight(nowPose, CameraPose(eye, rotation, nowPose.fov), Nanos.ofMillis(260))
        velocity.set(0.0, 0.0, 0.0)
    }

    fun wantsControl(viewportHovered: Boolean, keyboardFree: Boolean): Boolean {
        if (gesture != Gesture.NONE) return true
        if (!viewportHovered) return false
        if (!SdlWindow.isCreated) return false
        val buttons = SdlWindow.mouseButtons()
        if (buttons and SDLMouse.SDL_BUTTON_RMASK != 0) return true
        if (buttons and SDLMouse.SDL_BUTTON_MMASK != 0) return true
        val alt = down(SDLScancode.SDL_SCANCODE_LALT) || down(SDLScancode.SDL_SCANCODE_RALT)
        if (alt && buttons and SDLMouse.SDL_BUTTON_LMASK != 0) return true
        return pendingScroll != 0f
    }

    fun onFrame(deltaNanos: Long, viewportHovered: Boolean, keyboardFree: Boolean): CameraPose? {
        val eye = host.playerEyePose() ?: return null
        if (!SdlWindow.isCreated) return null
        val dt = (deltaNanos / Nanos.PER_SECOND.toDouble()).coerceIn(0.0, 0.1)
        var position = Vector3d(eye.position)
        yaw = eye.rotation.yaw
        pitch = eye.rotation.pitch
        val fov = settings.fov

        flight?.let { active ->
            active.elapsedNanos += deltaNanos
            val t = (active.elapsedNanos.toDouble() / active.durationNanos).coerceIn(0.0, 1.0)
            val eased = t * t * (3.0 - 2.0 * t)
            val pose = active.from.lerp(active.to, eased)
            if (t >= 1.0) flight = null
            pendingScroll = 0f
            return applyPose(pose)
        }

        SdlWindow.cursorPosition(cursor)
        val buttons = SdlWindow.mouseButtons()
        val alt = down(SDLScancode.SDL_SCANCODE_LALT) || down(SDLScancode.SDL_SCANCODE_RALT)
        val left = buttons and SDLMouse.SDL_BUTTON_LMASK != 0
        val right = buttons and SDLMouse.SDL_BUTTON_RMASK != 0
        val middle = buttons and SDLMouse.SDL_BUTTON_MMASK != 0
        val deltaX: Double
        val deltaY: Double
        if (gesture != Gesture.NONE) {
            SdlWindow.relativeMotion(motion)
            deltaX = motion[0]
            deltaY = motion[1]
        } else {
            deltaX = 0.0
            deltaY = 0.0
        }

        if (gesture == Gesture.NONE && viewportHovered) {
            val wanted = when {
                right && alt -> Gesture.DOLLY
                right -> Gesture.FLY
                middle -> Gesture.PAN
                left && alt -> Gesture.ORBIT
                else -> Gesture.NONE
            }
            if (wanted != Gesture.NONE) {
                gesture = wanted
                gestureTravel = 0.0
                pressCursorX = cursor[0]
                pressCursorY = cursor[1]
                if (wanted != Gesture.FLY) refreshPivot(position, Rotation(yaw, pitch))
                lockCursor(cursor[0], cursor[1])
                SdlWindow.relativeMotion(motion)
            }
        } else if (gesture != Gesture.NONE) {
            val stillHeld = when (gesture) {
                Gesture.FLY, Gesture.DOLLY -> right
                Gesture.PAN -> middle
                Gesture.ORBIT -> left
                Gesture.NONE -> false
            }
            if (!stillHeld) {
                gesture = Gesture.NONE
                unlockCursor()
                holdSeconds = 0.0
                gestureEndNanos = System.nanoTime()
            } else {
                gestureTravel += Math.abs(deltaX) + Math.abs(deltaY)
            }
        }

        val sensitivity = settings.freeSensitivity
        when (gesture) {
            Gesture.FLY -> {
                if (!settings.lockYaw) yaw += deltaX * sensitivity
                if (!settings.lockPitch) pitch = (pitch + deltaY * sensitivity).coerceIn(-90.0, 90.0)
                if (pendingScroll != 0f) {
                    settings.freeSpeed =
                        (settings.freeSpeed * Math.pow(1.15, pendingScroll.toDouble())).coerceIn(0.25, 400.0)
                    speedFlashUntilNanos = System.nanoTime() + Nanos.ofMillis(1500)
                }
            }

            Gesture.ORBIT -> {
                yaw += deltaX * sensitivity
                pitch = (pitch + deltaY * sensitivity).coerceIn(-89.0, 89.0)
                position = Vector3d(pivot).sub(Vector3d(Rotation(yaw, pitch).forward()).mul(pivotDistance))
            }

            Gesture.PAN -> {
                val rotation = Rotation(yaw, pitch)
                val scale = maxOf(0.5, pivotDistance) * 0.0022
                val up = Vector3d(rotation.right()).cross(rotation.forward()).normalize()
                position.add(Vector3d(rotation.right()).mul(-deltaX * scale)).add(Vector3d(up).mul(deltaY * scale))
                pivot.set(Vector3d(position).add(Vector3d(rotation.forward()).mul(pivotDistance)))
            }

            Gesture.DOLLY -> {
                val step = (deltaX + deltaY) * maxOf(0.5, pivotDistance) * 0.004
                position.add(Vector3d(Rotation(yaw, pitch).forward()).mul(step))
                pivotDistance = maxOf(0.25, pivotDistance - step)
            }

            Gesture.NONE -> if (viewportHovered && pendingScroll != 0f) {
                val ray = cursorRay() ?: Vector3d(Rotation(yaw, pitch).forward())
                val hit = rayHitDistance(position, ray) ?: pivotDistance
                val step = pendingScroll * maxOf(0.4, hit * 0.14)
                position.add(Vector3d(ray).mul(step.toDouble()))
                pivotDistance = maxOf(0.25, hit - step)
            }
        }
        pendingScroll = 0f

        val moving = updateVelocity(dt, keyboardFree && gesture == Gesture.FLY)
        if (moving || velocity.lengthSquared() > 1e-6) position.add(Vector3d(velocity).mul(dt))

        return applyPose(CameraPose(position, Rotation(yaw, pitch, settings.roll), fov))
    }

    private fun updateVelocity(dt: Double, allowKeys: Boolean): Boolean {
        var forward = 0.0
        var strafe = 0.0
        var vertical = 0.0
        if (allowKeys) {
            if (down(SDLScancode.SDL_SCANCODE_W)) forward += 1.0
            if (down(SDLScancode.SDL_SCANCODE_S)) forward -= 1.0
            if (down(SDLScancode.SDL_SCANCODE_D)) strafe += 1.0
            if (down(SDLScancode.SDL_SCANCODE_A)) strafe -= 1.0
            if (down(SDLScancode.SDL_SCANCODE_SPACE) || down(SDLScancode.SDL_SCANCODE_E)) vertical += 1.0
            if (down(SDLScancode.SDL_SCANCODE_LSHIFT) || down(SDLScancode.SDL_SCANCODE_Q)) vertical -= 1.0
        }
        val wants = forward != 0.0 || strafe != 0.0 || vertical != 0.0
        val target = Vector3d()
        if (wants) {
            val rotation = Rotation(yaw, 0.0)
            target.set(rotation.forward()).mul(forward).add(Vector3d(rotation.right()).mul(strafe))
            if (target.lengthSquared() > 1.0) target.normalize()
            target.add(0.0, vertical, 0.0)
            if (settings.lockX) target.x = 0.0
            if (settings.lockY) target.y = 0.0
            if (settings.lockZ) target.z = 0.0
            var speed = settings.freeSpeed
            if (down(SDLScancode.SDL_SCANCODE_LCTRL) || down(SDLScancode.SDL_SCANCODE_RCTRL)) speed *= 4.0
            if (down(SDLScancode.SDL_SCANCODE_LALT) && gesture == Gesture.FLY) speed *= 0.25
            holdSeconds += dt
            if (settings.freeAcceleration) speed *= 1.0 + 1.5 * minOf(1.0, holdSeconds / 2.5)
            target.mul(speed)
        } else {
            holdSeconds = 0.0
        }
        if (!settings.freeEasing) {
            velocity.set(target)
            return wants
        }
        val rate = if (wants) 14.0 else 18.0
        val blend = (1.0 - Math.exp(-rate * dt)).coerceIn(0.0, 1.0)
        velocity.lerp(target, blend)
        if (!wants && velocity.lengthSquared() < 1e-4) velocity.set(0.0, 0.0, 0.0)
        return wants
    }

    private fun refreshPivot(position: Vector3d, rotation: Rotation) {
        val ray = cursorRay() ?: rotation.forward()
        val distance = rayHitDistance(position, ray) ?: pivotDistance.coerceIn(2.0, 32.0)
        pivotDistance = distance
        pivot.set(Vector3d(position).add(Vector3d(rotation.forward()).mul(distance)))
    }

    private fun cursorRay(): Vector3d? {
        val bounds = viewport() ?: return null
        val width = SdlWindow.framebufferWidth
        val height = SdlWindow.framebufferHeight
        val windowWidth = SdlWindow.windowWidth
        val windowHeight = SdlWindow.windowHeight
        if (windowWidth <= 0 || windowHeight <= 0 || bounds[2] <= 0 || bounds[3] <= 0) return null
        val scaleX = width.toDouble() / windowWidth
        val scaleY = height.toDouble() / windowHeight
        val fx = cursor[0] * scaleX
        val fy = height - cursor[1] * scaleY
        val nx = ((fx - bounds[0]) / bounds[2]).toFloat()
        val ny = (1.0 - (fy - bounds[1]) / bounds[3]).toFloat()
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
        return host.ray(nx, ny)
    }

    private fun rayHitDistance(origin: Vector3d, direction: Vector3d): Double? = host.rayHitDistance(origin, direction, MAX_HIT_DISTANCE)

    private fun applyPose(pose: CameraPose): CameraPose {
        host.applyPose(pose)
        return pose
    }

    private fun down(scancode: Int): Boolean = SdlWindow.keyDown(scancode)

    private companion object {
        const val MAX_HIT_DISTANCE = 96.0
    }
}
