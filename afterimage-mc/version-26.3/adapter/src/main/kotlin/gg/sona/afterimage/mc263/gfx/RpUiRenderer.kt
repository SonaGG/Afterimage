package gg.sona.afterimage.mc263.gfx

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.IndexType
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.vertex.VertexFormat
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.gfx.TextureFormat
import gg.sona.afterimage.gfx.UiRenderer
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class RpUiRenderer(private val gfx: RenderpearlGfx) : UiRenderer {
    private class Command(val list: Int, val clipX: Int, val clipY: Int, val clipWidth: Int, val clipHeight: Int, val texture: Int, val elements: Int, val indexOffset: Int, val vertexOffset: Int)

    private val vertex = gfx.shaders.register("ui.vsh", VERTEX_SOURCE)
    private val fragment = gfx.shaders.register("ui.fsh", FRAGMENT_SOURCE)
    private val layout = BindGroupLayout.builder()
        .withUniform("Texture", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform(Glsl.PARAMS_BLOCK, UniformType.UNIFORM_BUFFER)
        .build()
    private val pipelines = HashMap<TextureFormat, RenderPipeline>()
    private val vertices = ArrayList<ByteBuffer>()
    private val indices = ArrayList<ByteBuffer>()
    private val commands = ArrayList<Command>()
    private var target: RpTarget? = null
    private var framebufferWidth = 0
    private var framebufferHeight = 0

    private fun pipeline(format: TextureFormat): RenderPipeline = pipelines.getOrPut(format) {
        RenderPipeline.builder()
            .withLocation(RpShaders.id("pipeline/ui/$format"))
            .withVertexShader(vertex)
            .withFragmentShader(fragment)
            .withBindGroupLayout(layout)
            .withColorTargetState(ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), RpReadback.gpuFormat(format), ColorTargetState.WRITE_ALL))
            .withVertexBinding(0, FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build()
    }

    override fun begin(target: Target?, framebufferWidth: Int, framebufferHeight: Int) {
        this.target = target as? RpTarget
        this.framebufferWidth = framebufferWidth
        this.framebufferHeight = framebufferHeight
        vertices.clear()
        indices.clear()
        commands.clear()
    }

    override fun uploadVertices(vertices: ByteBuffer) {
        this.vertices += copy(vertices)
    }

    override fun uploadIndices(indices: ByteBuffer) {
        this.indices += copy(indices)
    }

    private fun copy(source: ByteBuffer): ByteBuffer {
        val duplicate = source.duplicate()
        val result = MemoryUtil.memAlloc(duplicate.remaining())
        result.put(duplicate).flip()
        return result
    }

    override fun draw(clipX: Int, clipY: Int, clipWidth: Int, clipHeight: Int, texture: Int, elements: Int, indexOffset: Int, vertexOffset: Int) {
        if (elements == 0 || vertices.isEmpty()) return
        val left = clipX.coerceIn(0, framebufferWidth)
        val top = clipY.coerceIn(0, framebufferHeight)
        val right = (clipX + clipWidth).coerceIn(left, framebufferWidth)
        val bottom = (clipY + clipHeight).coerceIn(top, framebufferHeight)
        if (right <= left || bottom <= top) return
        commands += Command(vertices.size - 1, left, top, right - left, bottom - top, texture, elements, indexOffset, vertexOffset)
    }

    override fun end() {
        val target = this.target
        val color = target?.color
        try {
            if (target == null || color == null || commands.isEmpty() || vertices.size != indices.size) return
            val encoder = gfx.device.createCommandEncoder()
            val vertexBuffers = vertices.map { gfx.device.createBuffer({ "Afterimage UI vertices" }, GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_COPY_DST, it) }
            val indexBuffers = indices.map { gfx.device.createBuffer({ "Afterimage UI indices" }, GpuBuffer.USAGE_INDEX or GpuBuffer.USAGE_COPY_DST, it) }
            val projection = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
            orthographic(projection, framebufferWidth.toFloat(), framebufferHeight.toFloat())
            val uniforms = gfx.uniformSlice(encoder, projection)
            encoder.createRenderPass({ "Afterimage UI" }, color.view, Optional.empty(), null, OptionalDouble.empty(), RenderPass.RenderArea(0, 0, framebufferWidth, framebufferHeight)).use { pass ->
                pass.setPipeline(gfx.compile(pipeline(target.format)))
                pass.setUniform(Glsl.PARAMS_BLOCK, uniforms)
                var boundList = -1
                var boundTexture = -1
                for (command in commands) {
                    if (command.list != boundList) {
                        boundList = command.list
                        pass.setVertexBuffer(0, vertexBuffers[boundList].slice())
                        pass.setIndexBuffer(indexBuffers[boundList], IndexType.SHORT)
                    }
                    if (command.texture != boundTexture) {
                        boundTexture = command.texture
                        val texture = gfx.textureFor(command.texture) ?: continue
                        pass.setUniform("Texture", texture.view, texture.sampler)
                    }
                    pass.enableScissor(command.clipX, framebufferHeight - command.clipY - command.clipHeight, command.clipWidth, command.clipHeight)
                    pass.drawIndexed(command.elements, 1, command.indexOffset, command.vertexOffset, 0)
                }
            }
            for (buffer in vertexBuffers) gfx.defer(buffer)
            for (buffer in indexBuffers) gfx.defer(buffer)
        } finally {
            for (buffer in vertices) MemoryUtil.memFree(buffer)
            for (buffer in indices) MemoryUtil.memFree(buffer)
            vertices.clear()
            indices.clear()
            commands.clear()
            this.target = null
        }
    }

    private fun orthographic(into: ByteBuffer, width: Float, height: Float) {
        into.clear()
        into.putFloat(2f / width).putFloat(0f).putFloat(0f).putFloat(0f)
        into.putFloat(0f).putFloat(-2f / height).putFloat(0f).putFloat(0f)
        into.putFloat(0f).putFloat(0f).putFloat(-1f).putFloat(0f)
        into.putFloat(-1f).putFloat(1f).putFloat(0f).putFloat(1f)
        into.flip()
    }

    override fun close() = Unit

    companion object {
        val FORMAT: VertexFormat = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RG32_FLOAT)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA8_UNORM)
            .build()

        const val VERTEX_SOURCE = """
#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform AfterimageParams {
    mat4 ProjMtx;
};
layout(location = 0) in vec2 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 0) out vec2 fragUv;
layout(location = 1) out vec4 fragTint;
void main() {
    fragUv = UV0;
    fragTint = Color;
    gl_Position = ProjMtx * vec4(Position, 0.0, 1.0);
}
"""

        const val FRAGMENT_SOURCE = """
#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Texture;
layout(location = 0) in vec2 fragUv;
layout(location = 1) in vec4 fragTint;
layout(location = 0) out vec4 fragColor;
void main() {
    fragColor = fragTint * texture(Texture, fragUv);
}
"""
    }
}
