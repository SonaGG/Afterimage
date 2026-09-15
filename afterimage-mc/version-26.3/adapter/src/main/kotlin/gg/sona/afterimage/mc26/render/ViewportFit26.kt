package gg.sona.afterimage.mc26.render

import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.joml.Matrix4fc

class ViewportFit26(private val minecraft: Minecraft) {
    var viewport: () -> IntArray? = { null }
    var enabled: () -> Boolean = { false }
    private val remap = Matrix4f()
    private val scratch = Matrix4f()
    private var active = false

    fun update() {
        active = false
        if (!enabled()) return
        val rect = viewport() ?: return
        val width = minecraft.window.width
        val height = minecraft.window.height
        if (rect[2] <= 0 || rect[3] <= 0 || width <= 0 || height <= 0) return
        if (rect[0] == 0 && rect[1] == 0 && rect[2] == width && rect[3] == height) return
        val scaleX = rect[2].toFloat() / width
        val scaleY = rect[3].toFloat() / height
        val centerX = (rect[0] + rect[2] * 0.5f) / width * 2f - 1f
        val centerY = (rect[1] + rect[3] * 0.5f) / height * 2f - 1f
        remap.identity()
        remap.m00(scaleX)
        remap.m11(scaleY)
        remap.m30(centerX)
        remap.m31(centerY)
        active = true
    }

    private val live: Boolean get() = active && enabled()

    fun apply(projection: Matrix4f): Matrix4f {
        if (!live) return projection
        return remap.mul(projection, projection)
    }

    fun scissor(x: Int, y: Int, width: Int, height: Int): IntArray {
        if (!live) return intArrayOf(x, y, width, height)
        val rect = viewport() ?: return intArrayOf(x, y, width, height)
        val windowWidth = minecraft.window.width
        val windowHeight = minecraft.window.height
        val scaleX = rect[2].toFloat() / windowWidth
        val scaleY = rect[3].toFloat() / windowHeight
        val left = rect[0] + Math.round(x * scaleX)
        val bottom = rect[1] + Math.round(y * scaleY)
        val right = rect[0] + Math.round((x + width) * scaleX)
        val top = rect[1] + Math.round((y + height) * scaleY)
        return intArrayOf(left.coerceIn(0, windowWidth), bottom.coerceIn(0, windowHeight), (right - left).coerceIn(0, windowWidth - left.coerceIn(0, windowWidth)), (top - bottom).coerceIn(0, windowHeight - bottom.coerceIn(0, windowHeight)))
    }

    fun applied(projection: Matrix4fc): Matrix4fc {
        if (!live) return projection
        return remap.mul(projection, scratch)
    }
}
