package gg.sona.recast.editor.host

data class ViewportPick(
    val entityId: Int,
    val name: String,
    val isPlayer: Boolean,
    val isRecorder: Boolean,
    val uuid: String?,
    val x: Double,
    val y: Double,
    val z: Double,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val halfWidth: Double = 0.3,
    val height: Double = 1.8,
)
