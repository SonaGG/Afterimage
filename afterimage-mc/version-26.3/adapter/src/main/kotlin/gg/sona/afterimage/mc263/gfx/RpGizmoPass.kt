package gg.sona.afterimage.mc263.gfx

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.vertex.VertexFormat
import gg.sona.afterimage.gfx.GizmoGeometry
import gg.sona.afterimage.gfx.GizmoPass
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.gfx.TextureFormat
import gg.sona.afterimage.gfx.Viewport
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class RpGizmoPass(private val gfx: RenderpearlGfx) : GizmoPass {
    private enum class Depth(val compare: CompareOp, val write: Boolean) {
        OCCLUDED(CompareOp.LESS_THAN, false),
        VISIBLE(CompareOp.GREATER_THAN_OR_EQUAL, true),
        VISIBLE_NO_WRITE(CompareOp.GREATER_THAN_OR_EQUAL, false),
        OVERLAY(CompareOp.ALWAYS_PASS, false),
    }

    private class Batch(val topology: PrimitiveTopology, val depth: Depth, val data: ByteBuffer, val vertexCount: Int)

    private val vertex = gfx.shaders.register("gizmo.vsh", VERTEX_SOURCE)
    private val fragment = gfx.shaders.register("gizmo.fsh", FRAGMENT_SOURCE)
    private val layout = BindGroupLayout.builder().withUniform(Glsl.PARAMS_BLOCK, UniformType.UNIFORM_BUFFER).build()
    private val pipelines = HashMap<String, RenderPipeline>()

    private fun pipeline(format: TextureFormat, topology: PrimitiveTopology, depth: Depth): RenderPipeline =
        pipelines.getOrPut("$format/$topology/$depth") {
            RenderPipeline.builder()
                .withLocation(RpShaders.id("pipeline/gizmo/${pipelines.size}"))
                .withVertexShader(vertex)
                .withFragmentShader(fragment)
                .withBindGroupLayout(layout)
                .withColorTargetState(ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), RpReadback.gpuFormat(format), ColorTargetState.WRITE_ALL))
                .withDepthStencilState(DepthStencilState(depth.compare, depth.write))
                .withVertexBinding(0, FORMAT)
                .withPrimitiveTopology(topology)
                .withCull(false)
                .build()
        }

    override fun draw(
        target: Target?,
        viewport: Viewport,
        viewProjection: FloatArray,
        originX: Double,
        originY: Double,
        originZ: Double,
        geometry: GizmoGeometry,
    ) {
        val rp = target as? RpTarget ?: return
        val color = rp.color ?: return
        val depthView = rp.depthView ?: return
        val batches = ArrayList<Batch>()
        fun triangles(layer: Int, alpha: Float, depth: Depth) = pack(geometry, layer, alpha, originX, originY, originZ, true)?.let { batches += Batch(PrimitiveTopology.TRIANGLES, depth, it.first, it.second) }
        fun lines(layer: Int, alpha: Float, depth: Depth) = pack(geometry, layer, alpha, originX, originY, originZ, false)?.let { batches += Batch(PrimitiveTopology.DEBUG_LINES, depth, it.first, it.second) }
        triangles(WORLD, OCCLUDED_ALPHA, Depth.OCCLUDED)
        lines(WORLD, OCCLUDED_ALPHA, Depth.OCCLUDED)
        triangles(WORLD, 1f, Depth.VISIBLE)
        lines(WORLD, 1f, Depth.VISIBLE_NO_WRITE)
        triangles(OVERLAY, 1f, Depth.OVERLAY)
        lines(OVERLAY, 1f, Depth.OVERLAY)
        if (batches.isEmpty()) return
        val encoder = gfx.device.createCommandEncoder()
        val matrix = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
        val scaleX = viewport.width.toFloat() / rp.width
        val scaleY = viewport.height.toFloat() / rp.height
        val offsetX = (viewport.x + viewport.width * 0.5f) / rp.width * 2f - 1f
        val offsetY = (viewport.y + viewport.height * 0.5f) / rp.height * 2f - 1f
        for (column in 0 until 4) {
            val x = viewProjection[column * 4]
            val y = viewProjection[column * 4 + 1]
            val z = viewProjection[column * 4 + 2]
            val w = viewProjection[column * 4 + 3]
            matrix.putFloat(x * scaleX + w * offsetX).putFloat(y * scaleY + w * offsetY).putFloat(z).putFloat(w)
        }
        matrix.flip()
        val uniforms = gfx.uniformSlice(encoder, matrix)
        val buffers = batches.map { gfx.device.createBuffer({ "Afterimage gizmos" }, GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_COPY_DST, it.data) }
        try {
            encoder.createRenderPass({ "Afterimage gizmos" }, color.view, Optional.empty(), depthView, OptionalDouble.empty(), RenderPass.RenderArea(viewport.x, viewport.y, viewport.width, viewport.height)).use { pass ->
                pass.setUniform(Glsl.PARAMS_BLOCK, uniforms)
                for ((index, batch) in batches.withIndex()) {
                    pass.setPipeline(gfx.compile(pipeline(rp.format, batch.topology, batch.depth)))
                    pass.setVertexBuffer(0, buffers[index].slice())
                    pass.draw(batch.vertexCount, 1, 0, 0)
                }
            }
        } finally {
            for (buffer in buffers) gfx.defer(buffer)
            for (batch in batches) MemoryUtil.memFree(batch.data)
        }
    }

    private fun pack(geometry: GizmoGeometry, layer: Int, alpha: Float, ox: Double, oy: Double, oz: Double, triangles: Boolean): Pair<ByteBuffer, Int>? {
        val count = if (triangles) geometry.triCount else geometry.lineCount
        if (count == 0) return null
        val perPrimitive = if (triangles) 3 else 2
        val positions = if (triangles) geometry.triPositions else geometry.linePositions
        val colors = if (triangles) geometry.triColors else geometry.lineColors
        val layers = if (triangles) geometry.triLayers else geometry.lineLayers
        val data = MemoryUtil.memAlloc(count * perPrimitive * STRIDE)
        var written = 0
        for (index in 0 until count) {
            if (layers[index].toInt() != layer) continue
            val base = index * perPrimitive * 3
            val color = colors[index]
            for (corner in 0 until perPrimitive) {
                data.putFloat((positions[base + corner * 3] - ox).toFloat())
                data.putFloat((positions[base + corner * 3 + 1] - oy).toFloat())
                data.putFloat((positions[base + corner * 3 + 2] - oz).toFloat())
                data.put((color and 0xFF).toByte())
                data.put(((color shr 8) and 0xFF).toByte())
                data.put(((color shr 16) and 0xFF).toByte())
                data.put((((color ushr 24) and 0xFF) * alpha).toInt().coerceIn(0, 255).toByte())
            }
            written += perPrimitive
        }
        if (written == 0) {
            MemoryUtil.memFree(data)
            return null
        }
        data.flip()
        return data to written
    }

    override fun close() = Unit

    companion object {
        const val WORLD = 0
        const val OVERLAY = 1
        const val OCCLUDED_ALPHA = 0.22f
        const val STRIDE = 16

        val FORMAT: VertexFormat = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .build()

        const val VERTEX_SOURCE = """
#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform AfterimageParams {
    mat4 mvp;
};
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 0) out vec4 fragTint;
void main() {
    fragTint = Color;
    gl_Position = mvp * vec4(Position, 1.0);
}
"""

        const val FRAGMENT_SOURCE = """
#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location = 0) in vec4 fragTint;
layout(location = 0) out vec4 fragColor;
void main() {
    fragColor = fragTint;
}
"""
    }
}
