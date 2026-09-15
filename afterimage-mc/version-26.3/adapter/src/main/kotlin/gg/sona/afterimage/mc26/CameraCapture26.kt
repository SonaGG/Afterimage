package gg.sona.afterimage.mc26

import gg.sona.afterimage.capture.packet.PacketCapture
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.net.PacketWriter
import gg.sona.afterimage.protocol.AfterimageInternal
import gg.sona.afterimage.protocol.CameraFrame
import gg.sona.afterimage.protocol.InternalCodec
import gg.sona.afterimage.protocol.LocalPose
import gg.sona.afterimage.protocol.ServerboundPacket
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.state.level.CameraRenderState
import org.joml.Matrix4f
import org.joml.Matrix4fc

class CameraCapture26(private val minecraft: Minecraft) {
    var sampleRate: Int = 120
    var capture: () -> PacketCapture? = { null }

    private val writer = PacketWriter(256)
    private var lastSampleNanos = Long.MIN_VALUE
    var sampleAccepted = false
        private set
    private var viewCaptured = false
    private var handCaptured = false
    private val pendingView = FloatArray(16)
    private val pendingHand = FloatArray(16)
    private val pendingPosition = DoubleArray(3)
    private var pendingFov = 0f
    private val lastMatrix = FloatArray(16) { Float.NaN }
    private val lastHand = FloatArray(16) { Float.NaN }
    private val lastPosition = DoubleArray(3) { Double.NaN }
    private var lastFov = Float.NaN
    private var lastPoseX = Double.NaN
    private var lastPoseY = Double.NaN
    private var lastPoseZ = Double.NaN
    private var lastPoseYaw = Float.NaN
    private var lastPosePitch = Float.NaN
    private val view = Matrix4f()
    private val bob = Matrix4f()
    private var bobbed = false

    fun reset() {
        lastSampleNanos = Long.MIN_VALUE
        lastMatrix.fill(Float.NaN)
        lastHand.fill(Float.NaN)
        lastPosition.fill(Double.NaN)
        lastFov = Float.NaN
        lastPoseX = Double.NaN
        lastPoseY = Double.NaN
        lastPoseZ = Double.NaN
        lastPoseYaw = Float.NaN
        lastPosePitch = Float.NaN
        viewCaptured = false
        handCaptured = false
        bobbed = false
    }

    private fun sampleDue(): Boolean {
        if (sampleRate <= 0) return true
        val now = System.nanoTime()
        val interval = 1_000_000_000L / sampleRate
        if (lastSampleNanos != Long.MIN_VALUE && now - lastSampleNanos < interval) return false
        lastSampleNanos = if (lastSampleNanos == Long.MIN_VALUE || now - lastSampleNanos > interval * 2) now else lastSampleNanos + interval
        return true
    }

    fun onCameraExtracted(state: CameraRenderState, fov: Float) {
        viewCaptured = false
        handCaptured = false
        bobbed = false
        val capture = capture()?.takeIf { it.isRecording } ?: return
        val player = minecraft.player ?: return
        sampleAccepted = sampleDue()
        if (!sampleAccepted) return
        val partial = state.cameraEntityPartialTicks
        val feet = (minecraft.cameraEntity ?: player).getPosition(partial)
        val x = feet.x
        val y = feet.y
        val z = feet.z
        val yaw = player.getViewYRot(partial)
        val pitch = player.getViewXRot(partial)
        if (yaw != lastPoseYaw || pitch != lastPosePitch || x != lastPoseX || y != lastPoseY || z != lastPoseZ) {
            lastPoseYaw = yaw
            lastPosePitch = pitch
            lastPoseX = x
            lastPoseY = y
            lastPoseZ = z
            offer(capture, AfterimageInternal.LOCAL_POSE, LocalPose(x, y, z, yaw, pitch))
        }
        pendingPosition[0] = x
        pendingPosition[1] = y
        pendingPosition[2] = z
        pendingFov = fov
        view.set(state.viewRotationMatrix)
        view.translate((x - state.pos.x).toFloat(), (y - state.pos.y).toFloat(), (z - state.pos.z).toFloat())
        view.get(pendingView)
        viewCaptured = true
    }

    fun onBob(matrix: Matrix4fc) {
        if (!viewCaptured || bobbed) return
        bobbed = true
        bob.set(matrix)
        bob.mul(view, view)
        view.get(pendingView)
    }

    fun onHand(poseStack: Matrix4fc, state: CameraRenderState) {
        if (!viewCaptured) return
        Matrix4f(state.viewRotationMatrix).mul(poseStack).get(pendingHand)
        handCaptured = true
    }

    fun onFrameEnd() {
        if (!viewCaptured) return
        viewCaptured = false
        val capture = capture()?.takeIf { it.isRecording } ?: return
        var changed = pendingFov != lastFov
        for (index in 0 until 16) {
            if (pendingView[index] != lastMatrix[index]) {
                lastMatrix[index] = pendingView[index]
                changed = true
            }
        }
        for (index in 0 until 3) {
            if (pendingPosition[index] != lastPosition[index]) {
                lastPosition[index] = pendingPosition[index]
                changed = true
            }
        }
        if (handCaptured) {
            for (index in 0 until 16) {
                if (pendingHand[index] != lastHand[index]) {
                    lastHand[index] = pendingHand[index]
                    changed = true
                }
            }
        } else if (!lastHand[0].isNaN()) {
            lastHand.fill(Float.NaN)
            changed = true
        }
        if (!changed) return
        lastFov = pendingFov
        val hand = if (handCaptured) pendingHand.copyOf() else null
        offer(capture, AfterimageInternal.CAMERA_FRAME_COMPACT, CameraFrame(pendingView.copyOf(), pendingFov, pendingPosition.copyOf(), hand))
    }

    private fun offer(capture: PacketCapture, packetId: Int, packet: ServerboundPacket) {
        writer.reset()
        InternalCodec.encodeInto(packet, writer)
        capture.offer(PacketDirection.SERVERBOUND, packetId, writer.toByteArray())
    }
}
