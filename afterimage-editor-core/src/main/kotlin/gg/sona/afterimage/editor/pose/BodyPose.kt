package gg.sona.afterimage.editor.pose

data class BodyPose(val parts: Map<BodyPart, PartPose> = emptyMap()) {
    val isEmpty: Boolean get() = parts.isEmpty()

    operator fun get(part: BodyPart): PartPose? = parts[part]

    fun with(part: BodyPart, pose: PartPose): BodyPose = BodyPose(parts + (part to pose))

    fun without(part: BodyPart): BodyPose = BodyPose(parts - part)

    companion object {
        val EMPTY = BodyPose()
    }
}

