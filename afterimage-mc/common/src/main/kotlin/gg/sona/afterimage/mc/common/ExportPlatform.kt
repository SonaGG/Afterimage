package gg.sona.afterimage.mc.common

import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.gfx.Filter
import gg.sona.afterimage.gfx.Gfx
import gg.sona.afterimage.gfx.Target

interface ExportCamera {
    var exporting: Boolean
    var exportPose: CameraPose?
    var exportFov: Float
    var exportOrthoScale: Float?
    fun currentPose(): CameraPose
    fun exportPoseAt(nanos: Long): CameraPose
}

interface ExportSurface : AutoCloseable {
    val width: Int
    val height: Int
    val colorHandle: Int
    fun asTarget(): Target
    fun makeOpaque()
    fun setFilter(filter: Filter)
}

interface ExportPlatform {
    val gfx: Gfx
    val windowWidth: Int
    val windowHeight: Int
    fun createSurface(width: Int, height: Int, transparent: Boolean): ExportSurface
    fun beginRender(surface: ExportSurface)
    fun endRender()
    fun setHideGui(hide: Boolean): Boolean
    fun viewDistance(): Int
    fun calibrateChunks()
    fun chunksSettled(): Boolean
    fun pendingChunkDescription(): String
    fun missingSkins(): List<Int>
}
