package gg.sona.afterimage.mc263.gfx

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.renderpearl.api.commands.GpuFence
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFactor
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.api.vertex.VertexFormat
import gg.sona.afterimage.gfx.Blend
import gg.sona.afterimage.gfx.ColorMask
import gg.sona.afterimage.gfx.Filter
import gg.sona.afterimage.gfx.FullscreenPass
import gg.sona.afterimage.gfx.Gfx
import gg.sona.afterimage.gfx.GizmoPass
import gg.sona.afterimage.gfx.Readback
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.gfx.Texture
import gg.sona.afterimage.gfx.Texture3D
import gg.sona.afterimage.gfx.TextureFormat
import gg.sona.afterimage.gfx.UiRenderer
import gg.sona.afterimage.gfx.Uniforms
import gg.sona.afterimage.gfx.Viewport
import net.minecraft.resources.Identifier
import org.joml.Vector4f
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.Executor

// we'll commonise more of this one day

class RenderpearlGfx(private val warn: (String) -> Unit = {}) : Gfx, AutoCloseable {
    override val glslVersion: Int get() = 330

    val device: GpuDevice get() = RenderSystem.getDevice()

    val shaders = RpShaders()
    private val fullscreenVertex = shaders.register("fullscreen.vsh", Glsl.FULLSCREEN_VERTEX)
    private val compiled = HashMap<RenderPipeline, CompiledRenderPipeline>()
    private val handles = HashMap<Int, RpTexture>()
    private val wrapped = WeakHashMap<GpuTexture, RpTexture>()
    private var nextHandle = 1
    private var nextPass = 0
    private val pending = ArrayList<AutoCloseable>()

    private val blitPass by lazy { createPassInternal("blit", BLIT_SOURCE, listOf("tex")) }
    private val fillPass by lazy { createPassInternal("fill", FILL_SOURCE, listOf("fill")) }
    private val depthPass by lazy { createPassInternal("depth", DEPTH_SOURCE, listOf("depth", "rect")) }

    fun register(texture: RpTexture): Int {
        val handle = nextHandle++
        handles[handle] = texture
        return handle
    }

    fun unregister(texture: RpTexture) {
        handles.remove(texture.handle)
    }

    fun textureFor(handle: Int): RpTexture? = handles[handle]

    fun wrap(texture: GpuTexture, format: TextureFormat = TextureFormat.RGBA8): RpTexture =
        wrapped.getOrPut(texture) { RpTexture(this, texture, device.createTextureView(texture), format, owned = false, Filter.LINEAR) }

    fun wrap(target: RenderTarget): RpTarget {
        val color = target.colorTexture?.let { wrap(it) }
        return RpTarget(color, target.depthTexture, target.depthTextureView, target.width, target.height, owned = false)
    }

    fun compile(pipeline: RenderPipeline): CompiledRenderPipeline = compiled.getOrPut(pipeline) {
        device.compilePipeline(pipeline, shaders, Executor { it.run() }).join().finishCompile()
            ?: throw IllegalStateException("could not compile pipeline ${pipeline.location}")
    }

    fun defer(closeable: AutoCloseable) {
        pending += closeable
    }

    fun endFrame() {
        for (closeable in pending) runCatching { closeable.close() }
        pending.clear()
    }

    override fun createTexture(width: Int, height: Int, format: TextureFormat, filter: Filter): Texture {
        val mips = if (format == TextureFormat.RGBA8 || format == TextureFormat.RGBA16) mipLevelsFor(width, height) else 1
        val usage = GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_SRC or GpuTexture.USAGE_COPY_DST
        val texture = device.createTexture({ "Afterimage texture" }, usage, RpReadback.gpuFormat(format), width, height, 1, mips)
        return RpTexture(this, texture, device.createTextureView(texture), format, owned = true, filter)
    }

    override fun uploadTexture(width: Int, height: Int, rgba: ByteBuffer, filter: Filter, mipmaps: Boolean): Texture {
        val texture = createTexture(width, height, TextureFormat.RGBA8, filter) as RpTexture
        device.createCommandEncoder().writeToTexture(texture.texture, rgba, 0, 0, 0, 0, width, height)
        if (mipmaps) generateMipmaps(texture)
        return texture
    }

    override fun uploadTextureLevels(width: Int, height: Int, levels: List<ByteBuffer>, filter: Filter): Texture {
        val texture = createTexture(width, height, TextureFormat.RGBA8, filter) as RpTexture
        val encoder = device.createCommandEncoder()
        var levelWidth = width
        var levelHeight = height
        for (level in levels.indices) {
            if (level >= texture.mipLevels) break
            encoder.writeToTexture(texture.texture, levels[level], level, 0, 0, 0, levelWidth, levelHeight)
            levelWidth = levelWidth shr 1
            levelHeight = levelHeight shr 1
        }
        return texture
    }

    override fun createTexture3D(size: Int, rgb: FloatArray): Texture3D {
        val usage = GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST
        val texture = device.createTexture({ "Afterimage LUT" }, usage, GpuFormat.RGBA16_UNORM, size * size, size, 1, 1)
        val encoder = device.createCommandEncoder()
        val layer = MemoryUtil.memAlloc(size * size * 8)
        try {
            for (z in 0 until size) {
                layer.clear()
                for (index in 0 until size * size) {
                    val base = (z * size * size + index) * 3
                    for (channel in 0 until 3) layer.putShort((rgb[base + channel].coerceIn(0f, 1f) * 65535f + 0.5f).toInt().toShort())
                    layer.putShort(-1)
                }
                layer.flip()
                encoder.writeToTexture(texture, layer, 0, 0, z * size, 0, size, size)
            }
        } finally {
            MemoryUtil.memFree(layer)
        }
        return RpTexture3D(texture, device.createTextureView(texture), size)
    }

    override fun createTarget(width: Int, height: Int, format: TextureFormat, filter: Filter): Target =
        RpTarget(createTexture(width, height, format, filter) as RpTexture, null, null, width, height, owned = true)

    override fun createReadback(target: Target, slots: Int): Readback = RpReadback(this, target as RpTarget, slots)

    override fun createPass(fragmentSource: String, uniforms: List<String>): FullscreenPass = createPassInternal("pass${nextPass++}", fragmentSource, uniforms)

    private fun createPassInternal(name: String, fragmentSource: String, uniforms: List<String>): RpFullscreenPass {
        val translated = Glsl.fragment(fragmentSource)
        val fragment = shaders.register("$name.fsh", translated.source)
        return RpFullscreenPass(this, name, fullscreenVertex, fragment, translated)
    }

    override fun createUiRenderer(): UiRenderer = RpUiRenderer(this)

    override fun createGizmoPass(): GizmoPass = RpGizmoPass(this)

    override fun blit(source: Texture, target: Target?, viewport: Viewport, blend: Blend, filter: Filter) {
        val texture = source as RpTexture
        if (filter == Filter.LINEAR_MIPMAP) generateMipmaps(texture)
        texture.setFilter(filter)
        val weight = (blend as? Blend.Accumulate)?.weight ?: 1f
        blitPass.draw(target, viewport, blend, ColorTargetState.WRITE_ALL) {
            texture("tex", 0, texture)
            float("weight", weight)
        }
    }

    override fun copyColor(source: Target?, x: Int, y: Int, width: Int, height: Int, into: Texture) {
        val from = (source as? RpTarget)?.color?.texture ?: return
        device.createCommandEncoder().copyTextureToTexture(from, (into as RpTexture).texture, 0, 0, 0, x, y, width, height)
    }

    override fun copyDepth(source: Target?, x: Int, y: Int, width: Int, height: Int, into: Texture): Boolean {
        val from = source as? RpTarget ?: return false
        val depth = from.depth ?: return false
        val depthView = from.depthView ?: return false
        val destination = into as RpTexture
        val depthTexture = RpTexture(this, depth, depthView, TextureFormat.DEPTH24, owned = false, Filter.NEAREST)
        try {
            depthPass.draw(RpTarget(destination, null, null, destination.width, destination.height, owned = false), Viewport(0, 0, width, height), Blend.None, ColorTargetState.WRITE_ALL) {
                texture("depth", 0, depthTexture)
                vec4("rect", x.toFloat() / from.width, y.toFloat() / from.height, width.toFloat() / from.width, height.toFloat() / from.height)
            }
        } finally {
            unregister(depthTexture)
        }
        return true
    }

    private fun awaitCopies(encoder: CommandEncoder) {
        val fence = encoder.createFence()
        try {
            fence.awaitCompletion(GpuFence.NO_TIMEOUT)
        } catch (error: IllegalStateException) {
        } finally {
            fence.close()
        }
    }

    override fun readPixels(source: Target?, x: Int, y: Int, width: Int, height: Int, into: ByteBuffer) {
        val texture = (source as? RpTarget)?.color?.texture ?: return
        val bytes = width.toLong() * height * texture.format.blockSize()
        val buffer = device.createBuffer({ "Afterimage readPixels" }, GpuBuffer.USAGE_MAP_READ or GpuBuffer.USAGE_COPY_DST, bytes)
        try {
            val encoder = device.createCommandEncoder()
            encoder.copyTextureToBuffer(texture, buffer, 0L, {}, 0, x, y, width, height)
            awaitCopies(encoder)
            buffer.map(true, false).use { view ->
                val data = view.data()
                into.clear()
                if (texture.format.blockSize() == 4) {
                    data.limit(minOf(data.capacity(), into.remaining()))
                    into.put(data)
                } else {
                    for (index in 0 until width * height * 4) into.put(data.get(index * 2 + 1))
                }
                into.flip()
            }
        } finally {
            buffer.close()
        }
    }

    override fun clear(target: Target?, red: Float, green: Float, blue: Float, alpha: Float, mask: ColorMask, depth: Boolean) {
        val rp = target as? RpTarget ?: return
        val color = rp.color
        if (color != null) {
            if (mask == ColorMask.ALL) {
                device.createCommandEncoder().clearColorTexture(color.texture, Vector4f(red, green, blue, alpha))
            } else {
                fillPass.draw(rp, Viewport(0, 0, rp.width, rp.height), Blend.None, ColorTargetState.WRITE_ALPHA) { vec4("fill", red, green, blue, alpha) }
            }
        }
        if (depth) rp.depth?.let { device.createCommandEncoder().clearDepthTexture(it, RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE) }
    }

    override fun <T> preservingTarget(block: () -> T): T = block()

    fun generateMipmaps(texture: RpTexture) {
        if (texture.mipLevels <= 1) return
        val previous = texture.filter
        texture.setFilter(Filter.LINEAR)
        try {
            for (level in 1 until texture.mipLevels) {
                val width = texture.texture.getWidth(level)
                val height = texture.texture.getHeight(level)
                blitPass.drawInto(texture.mipView(level), width, height, texture.format, Blend.None, ColorTargetState.WRITE_ALL, Viewport(0, 0, width, height)) {
                    sampler("tex", texture.mipView(level - 1), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, false))
                    float("weight", 1f)
                }
            }
        } finally {
            texture.setFilter(previous)
        }
    }

    fun uniformSlice(encoder: CommandEncoder, data: ByteBuffer): GpuBufferSlice =
        encoder.transientMemory().uploadStaging(data, UNIFORM_ALIGNMENT, GpuBuffer.USAGE_UNIFORM)

    fun viewportSlice(encoder: CommandEncoder, viewport: Viewport, width: Int, height: Int): GpuBufferSlice {
        val data = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        data.putFloat(viewport.x.toFloat() / width).putFloat(viewport.y.toFloat() / height).putFloat(viewport.width.toFloat() / width).putFloat(viewport.height.toFloat() / height).flip()
        return uniformSlice(encoder, data)
    }

    override fun close() {
        endFrame()
        for (pipeline in compiled.values) runCatching { pipeline.close() }
        compiled.clear()
        shaders.close()
    }

    companion object {
        const val UNIFORM_ALIGNMENT = 256L

        fun mipLevelsFor(width: Int, height: Int): Int {
            var levels = 1
            var size = minOf(width, height)
            while (size > 1) {
                size /= 2
                levels++
            }
            return levels
        }

        fun blendFunction(blend: Blend): Optional<BlendFunction> = when (blend) {
            Blend.None -> Optional.empty()
            Blend.Alpha -> Optional.of(BlendFunction.TRANSLUCENT)
            Blend.Premultiplied -> Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
            is Blend.Accumulate -> Optional.of(BlendFunction.ADDITIVE)
        }

        fun colorTarget(format: TextureFormat, blend: Blend, writeMask: Int): ColorTargetState =
            ColorTargetState(blendFunction(blend), RpReadback.gpuFormat(format), writeMask)

        val BLIT_SOURCE = """
uniform sampler2D tex;
uniform float weight;
varying vec2 uv;
void main() {
    gl_FragColor = texture2D(tex, uv) * weight;
}
"""

        val FILL_SOURCE = """
uniform vec4 fill;
varying vec2 uv;
void main() {
    gl_FragColor = fill;
}
"""

        val DEPTH_SOURCE = """
uniform sampler2D depth;
uniform vec4 rect;
varying vec2 uv;
void main() {
    float reversed = texture2D(depth, rect.xy + uv * rect.zw).r;
    gl_FragColor = vec4(1.0 - reversed, 0.0, 0.0, 1.0);
}
"""
    }
}

class RpUniforms(private val gfx: RenderpearlGfx, private val pass: RenderPass, private val translated: TranslatedFragment, private val block: ByteBuffer?) : Uniforms {
    override fun texture(name: String, unit: Int, texture: Texture?) {
        val rp = texture as? RpTexture ?: return
        pass.setUniform(name, rp.view, rp.sampler)
    }

    fun sampler(name: String, view: GpuTextureView, sampler: GpuSampler) = pass.setUniform(name, view, sampler)

    override fun texture3D(name: String, unit: Int, texture: Texture3D?) {
        val rp = texture as? RpTexture3D ?: return
        pass.setUniform(name, rp.view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, false))
    }

    private fun member(name: String): Std140Member? = translated.members.firstOrNull { it.name == name }

    override fun int(name: String, value: Int) {
        val member = member(name) ?: return
        block?.putInt(member.offset, value)
    }

    override fun float(name: String, value: Float) {
        val member = member(name) ?: return
        block?.putFloat(member.offset, value)
    }

    override fun vec2(name: String, x: Float, y: Float) {
        val member = member(name) ?: return
        block?.putFloat(member.offset, x)?.putFloat(member.offset + 4, y)
    }

    fun vec4(name: String, x: Float, y: Float, z: Float, w: Float) {
        val member = member(name) ?: return
        block?.putFloat(member.offset, x)?.putFloat(member.offset + 4, y)?.putFloat(member.offset + 8, z)?.putFloat(member.offset + 12, w)
    }

    fun mat4(name: String, values: FloatArray) {
        val member = member(name) ?: return
        val buffer = block ?: return
        for (index in 0 until 16) buffer.putFloat(member.offset + index * 4, values[index])
    }
}

class RpFullscreenPass(
    private val gfx: RenderpearlGfx,
    private val name: String,
    private val vertex: Identifier,
    private val fragment: Identifier,
    private val translated: TranslatedFragment,
) : FullscreenPass {
    private val pipelines = HashMap<String, RenderPipeline>()
    private val layout: BindGroupLayout = BindGroupLayout.builder().apply {
        for ((_, sampler) in translated.samplers) withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER)
        if (translated.members.isNotEmpty()) withUniform(Glsl.PARAMS_BLOCK, UniformType.UNIFORM_BUFFER)
        withUniform(Glsl.VIEWPORT_BLOCK, UniformType.UNIFORM_BUFFER)
    }.build()

    fun pipeline(format: TextureFormat, blend: Blend, writeMask: Int): CompiledRenderPipeline {
        val key = "$format/$blend/$writeMask"
        val pipeline = pipelines.getOrPut(key) {
            RenderPipeline.builder()
                .withLocation(RpShaders.id("pipeline/$name/${pipelines.size}"))
                .withVertexShader(vertex)
                .withFragmentShader(fragment)
                .withBindGroupLayout(layout)
                .withColorTargetState(RenderpearlGfx.colorTarget(format, blend, writeMask))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build()
        }
        return gfx.compile(pipeline)
    }

    override fun draw(target: Target?, viewport: Viewport, blend: Blend, bind: Uniforms.() -> Unit) =
        draw(target, viewport, blend, ColorTargetState.WRITE_ALL) { bind(this) }

    fun draw(target: Target?, viewport: Viewport, blend: Blend, writeMask: Int, bind: RpUniforms.() -> Unit) {
        val rp = target as? RpTarget ?: return
        val color = rp.color ?: return
        drawInto(color.view, rp.width, rp.height, rp.format, blend, writeMask, viewport, bind)
    }

    fun drawInto(view: GpuTextureView, width: Int, height: Int, format: TextureFormat, blend: Blend, writeMask: Int, viewport: Viewport, bind: RpUniforms.() -> Unit) {
        if (viewport.isEmpty) return
        val encoder = gfx.device.createCommandEncoder()
        val block = if (translated.blockSize > 0) ByteBuffer.allocateDirect(translated.blockSize).order(ByteOrder.nativeOrder()) else null
        val area = RenderPass.RenderArea(
            viewport.x.coerceIn(0, width),
            viewport.y.coerceIn(0, height),
            viewport.width.coerceAtMost(width - viewport.x.coerceIn(0, width)),
            viewport.height.coerceAtMost(height - viewport.y.coerceIn(0, height)),
        )
        encoder.createRenderPass({ "Afterimage $name" }, view, Optional.empty(), null, OptionalDouble.empty(), area).use { pass ->
            pass.setPipeline(pipeline(format, blend, writeMask))
            pass.setUniform(Glsl.VIEWPORT_BLOCK, gfx.viewportSlice(encoder, viewport, width, height))
            val uniforms = RpUniforms(gfx, pass, translated, block)
            uniforms.bind()
            if (block != null) {
                block.clear()
                pass.setUniform(Glsl.PARAMS_BLOCK, gfx.uniformSlice(encoder, block))
            }
            pass.draw(3, 1, 0, 0)
        }
    }

    override fun close() = Unit
}
