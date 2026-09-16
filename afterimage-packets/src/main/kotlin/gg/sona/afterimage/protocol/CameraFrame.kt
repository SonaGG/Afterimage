package gg.sona.afterimage.protocol


class CameraFrame(
    val modelView: FloatArray,
    val fov: Float,
    val position: DoubleArray? = null,
    val hand: FloatArray? = null,
) : ServerboundPacket {
    override val packetId: Int get() = AfterimageInternal.CAMERA_FRAME_COMPACT

    init {
        require(modelView.size == 16) { "camera frame needs a 4x4 matrix" }
        require(position == null || position.size == 3) { "camera frame position needs three doubles" }
        require(hand == null || hand.size == 16) { "camera frame hand needs a 4x4 matrix" }
    }

    override fun equals(other: Any?): Boolean =
        other is CameraFrame && fov == other.fov && modelView.contentEquals(other.modelView) &&
                (position?.contentEquals(other.position)
                    ?: (other.position == null)) && (hand?.contentEquals(other.hand) ?: (other.hand == null))

    override fun hashCode(): Int =
        ((modelView.contentHashCode() * 31 + fov.hashCode()) * 31 + (position?.contentHashCode()
            ?: 0)) * 31 + (hand?.contentHashCode() ?: 0)

    companion object {
        const val FLAG_POSITION = 1
        const val FLAG_HAND = 2
        const val FLAG_VIEW_COMPACT = 4
        const val FLAG_HAND_COMPACT = 8
    }
}
