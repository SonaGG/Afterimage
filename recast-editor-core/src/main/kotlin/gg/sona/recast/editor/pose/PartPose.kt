package gg.sona.recast.editor.pose

data class PartPose(val x: Double, val y: Double, val z: Double, val weight: Double = 1.0) {
    companion object {
        val REST = PartPose(0.0, 0.0, 0.0, 1.0)
    }
}
