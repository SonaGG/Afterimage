package gg.sona.afterimage.protocol


data class LocalPose(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float) :
    ServerboundPacket {
    override val packetId: Int get() = AfterimageInternal.LOCAL_POSE
}
