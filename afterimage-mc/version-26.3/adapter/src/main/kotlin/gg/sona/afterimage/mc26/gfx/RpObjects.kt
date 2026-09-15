package gg.sona.afterimage.mc26.gfx

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.GpuFence
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import gg.sona.afterimage.gfx.Filter
import gg.sona.afterimage.gfx.Readback
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.gfx.Texture
import gg.sona.afterimage.gfx.Texture3D
import gg.sona.afterimage.gfx.TextureFormat

class RpTexture(
    private val gfx: RenderpearlGfx,
    val texture: GpuTexture,
    val view: GpuTextureView,
    val format: TextureFormat,
    private val owned: Boolean,
    filter: Filter,
) : Texture {
    override val handle: Int = gfx.register(this)
    override val width: Int get() = texture.getWidth(0)
    override val height: Int get() = texture.getHeight(0)
    var filter: Filter = filter
        private set
    private var closed = false
    private val mipViews = HashMap<Int, GpuTextureView>()

    val mipLevels: Int get() = texture.mipLevels

    val sampler: GpuSampler
        get() = RenderSystem.getSamplerCache().getClampToEdge(
            if (filter == Filter.NEAREST) FilterMode.NEAREST else FilterMode.LINEAR,
            filter == Filter.LINEAR_MIPMAP && mipLevels > 1,
        )

    fun mipView(level: Int): GpuTextureView = mipViews.getOrPut(level) { gfx.device.createTextureView(texture, level, 1) }

    override fun setFilter(filter: Filter) {
        this.filter = filter
    }

    override fun generateMipmaps() = gfx.generateMipmaps(this)

    override fun close() {
        if (closed) return
        closed = true
        gfx.unregister(this)
        for (view in mipViews.values) view.close()
        mipViews.clear()
        if (owned) {
            view.close()
            texture.close()
        }
    }
}

class RpTexture3D(val texture: GpuTexture, val view: GpuTextureView, override val size: Int) : Texture3D {
    override val handle: Int get() = 0

    override fun close() {
        view.close()
        texture.close()
    }
}

class RpTarget(
    override val color: RpTexture?,
    val depth: GpuTexture?,
    val depthView: GpuTextureView?,
    override val width: Int,
    override val height: Int,
    private val owned: Boolean,
) : Target {
    val format: TextureFormat get() = color?.format ?: TextureFormat.RGBA8

    override fun close() {
        if (!owned) return
        color?.close()
        depthView?.close()
        depth?.close()
    }
}

class RpReadback(private val gfx: RenderpearlGfx, private val target: RpTarget, override val slots: Int) : Readback {
    private val texture = target.color?.texture ?: throw IllegalArgumentException("readback target has no colour texture")
    private val bytesPerPixel = texture.format.blockSize()
    private val bytes = target.width.toLong() * target.height * bytesPerPixel
    private val buffers = Array(slots) { gfx.device.createBuffer({ "Afterimage readback" }, GpuBuffer.USAGE_MAP_READ or GpuBuffer.USAGE_COPY_DST, bytes) }
    private val fences = arrayOfNulls<GpuFence>(slots)

    override fun issue(slot: Int) {
        fences[slot]?.close()
        val encoder = gfx.device.createCommandEncoder()
        encoder.copyTextureToBuffer(texture, buffers[slot], 0L, {}, 0)
        fences[slot] = encoder.createFence()
    }

    private inline fun <T> mapped(slot: Int, block: (java.nio.ByteBuffer) -> T): T {
        fences[slot]?.let {
            try {
                it.awaitCompletion(GpuFence.NO_TIMEOUT)
            } catch (error: IllegalStateException) {
            }
            it.close()
            fences[slot] = null
        }
        buffers[slot].map(true, false).use { view -> return block(view.data()) }
    }

    override fun collectRgba(slot: Int, into: ByteArray) {
        val width = target.width
        val height = target.height
        val rowBytes = width * bytesPerPixel
        mapped(slot) { data ->
            if (bytesPerPixel == 4) {
                for (row in 0 until height) {
                    data.position((height - 1 - row) * rowBytes)
                    data.get(into, row * rowBytes, rowBytes)
                }
            } else {
                for (row in 0 until height) {
                    val source = (height - 1 - row) * rowBytes
                    val destination = row * width * 4
                    for (x in 0 until width) {
                        val pixel = source + x * bytesPerPixel
                        for (channel in 0 until 4) into[destination + x * 4 + channel] = data.get(pixel + channel * 2 + 1)
                    }
                }
            }
        }
    }

    override fun collectR16(slot: Int, into: FloatArray) {
        val width = target.width
        val height = target.height
        mapped(slot) { data ->
            val shorts = data.asShortBuffer()
            for (row in 0 until height) {
                val source = (height - 1 - row) * width
                val destination = row * width
                for (x in 0 until width) into[destination + x] = (shorts.get(source + x).toInt() and 0xFFFF) / 65535f
            }
        }
    }

    override fun close() {
        for (fence in fences) fence?.close()
        for (buffer in buffers) buffer.close()
    }

    companion object {
        fun gpuFormat(format: TextureFormat): GpuFormat = when (format) {
            TextureFormat.RGBA8 -> GpuFormat.RGBA8_UNORM
            TextureFormat.RGBA16 -> GpuFormat.RGBA16_UNORM
            TextureFormat.R16 -> GpuFormat.R16_UNORM
            TextureFormat.DEPTH24 -> GpuFormat.R32_FLOAT
        }
    }
}
