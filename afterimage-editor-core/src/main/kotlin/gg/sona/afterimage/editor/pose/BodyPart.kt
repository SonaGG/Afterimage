package gg.sona.afterimage.editor.pose

enum class BodyPart(
    val label: String,
    val pivotX: Double,
    val pivotY: Double,
    val pivotZ: Double,
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    HEAD("Head", 0.0, 0.0, 0.0, -4.0, -8.0, -4.0, 4.0, 0.0, 4.0),
    BODY("Body", 0.0, 0.0, 0.0, -4.0, 0.0, -2.0, 4.0, 12.0, 2.0),
    RIGHT_ARM("Right arm", -5.0, 2.0, 0.0, -3.0, -2.0, -2.0, 1.0, 10.0, 2.0),
    LEFT_ARM("Left arm", 5.0, 2.0, 0.0, -1.0, -2.0, -2.0, 3.0, 10.0, 2.0),
    RIGHT_LEG("Right leg", -1.9, 12.0, 0.0, -2.0, 0.0, -2.0, 2.0, 12.0, 2.0),
    LEFT_LEG("Left leg", 1.9, 12.0, 0.0, -2.0, 0.0, -2.0, 2.0, 12.0, 2.0);

    companion object {
        fun of(ordinal: Int): BodyPart = entries[ordinal.coerceIn(0, entries.size - 1)]
    }
}
