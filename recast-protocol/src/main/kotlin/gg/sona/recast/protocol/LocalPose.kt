package gg.sona.recast.protocol


data class LocalPose(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float) :
    ServerboundPacket {
    override val packetId: Int get() = RecastInternal.LOCAL_POSE
}
