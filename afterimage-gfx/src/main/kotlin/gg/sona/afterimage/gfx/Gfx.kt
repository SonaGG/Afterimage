package gg.sona.afterimage.gfx

import java.nio.ByteBuffer

enum class TextureFormat { RGBA8, RGBA16, R16, DEPTH24 }

enum class Filter { NEAREST, LINEAR, LINEAR_MIPMAP }

enum class ColorMask { ALL, ALPHA }

sealed class Blend {
    object None : Blend()
    object Alpha : Blend()
    object Premultiplied : Blend()
    class Accumulate(val weight: Float) : Blend()
}

class Viewport(val x: Int, val y: Int, val width: Int, val height: Int) {
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

interface Texture : AutoCloseable {
    val handle: Int
    val width: Int
    val height: Int
    fun setFilter(filter: Filter)
    fun generateMipmaps()
}

interface Texture3D : AutoCloseable {
    val handle: Int
    val size: Int
}

interface Target : AutoCloseable {
    val width: Int
    val height: Int
    val color: Texture?
}

interface Readback : AutoCloseable {
    val slots: Int
    fun issue(slot: Int)
    fun collectRgba(slot: Int, into: ByteArray)
    fun collectR16(slot: Int, into: FloatArray)
}

interface Uniforms {
    fun texture(name: String, unit: Int, texture: Texture?)
    fun texture3D(name: String, unit: Int, texture: Texture3D?)
    fun int(name: String, value: Int)
    fun float(name: String, value: Float)
    fun vec2(name: String, x: Float, y: Float)
}

interface FullscreenPass : AutoCloseable {
    fun draw(target: Target?, viewport: Viewport, blend: Blend = Blend.None, bind: Uniforms.() -> Unit)
}

interface UiRenderer : AutoCloseable {
    fun begin(target: Target?, framebufferWidth: Int, framebufferHeight: Int)
    fun uploadVertices(vertices: ByteBuffer)
    fun uploadIndices(indices: ByteBuffer)
    fun draw(clipX: Int, clipY: Int, clipWidth: Int, clipHeight: Int, texture: Int, elements: Int, indexOffset: Int, vertexOffset: Int)
    fun end()
}

interface GizmoGeometry {
    val lineCount: Int
    val linePositions: DoubleArray
    val lineColors: IntArray
    val lineWidths: FloatArray
    val lineLayers: ByteArray
    val triCount: Int
    val triPositions: DoubleArray
    val triColors: IntArray
    val triLayers: ByteArray
}

interface GizmoPass : AutoCloseable {
    fun draw(target: Target?, viewport: Viewport, viewProjection: FloatArray, originX: Double, originY: Double, originZ: Double, geometry: GizmoGeometry)
}

interface Gfx {
    val glslVersion: Int

    fun createTexture(width: Int, height: Int, format: TextureFormat, filter: Filter = Filter.NEAREST): Texture

    fun uploadTexture(width: Int, height: Int, rgba: ByteBuffer, filter: Filter, mipmaps: Boolean = false): Texture

    fun uploadTextureLevels(width: Int, height: Int, levels: List<ByteBuffer>, filter: Filter): Texture

    fun createTexture3D(size: Int, rgb: FloatArray): Texture3D

    fun createTarget(width: Int, height: Int, format: TextureFormat = TextureFormat.RGBA8, filter: Filter = Filter.NEAREST): Target

    fun createReadback(target: Target, slots: Int): Readback

    fun createPass(fragmentSource: String, uniforms: List<String>): FullscreenPass

    fun createUiRenderer(): UiRenderer

    fun createGizmoPass(): GizmoPass

    fun blit(source: Texture, target: Target?, viewport: Viewport, blend: Blend = Blend.None, filter: Filter = Filter.NEAREST)

    fun copyColor(source: Target?, x: Int, y: Int, width: Int, height: Int, into: Texture)

    fun copyDepth(source: Target?, x: Int, y: Int, width: Int, height: Int, into: Texture): Boolean

    fun readPixels(source: Target?, x: Int, y: Int, width: Int, height: Int, into: ByteBuffer)

    fun clear(target: Target?, red: Float, green: Float, blue: Float, alpha: Float, mask: ColorMask = ColorMask.ALL, depth: Boolean = false)

    fun <T> preservingTarget(block: () -> T): T
}
