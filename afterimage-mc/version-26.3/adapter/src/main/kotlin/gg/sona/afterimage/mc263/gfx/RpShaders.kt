package gg.sona.afterimage.mc263.gfx

import com.mojang.renderpearl.api.pipeline.ShaderSource
import com.mojang.renderpearl.api.pipeline.ShaderType
import net.minecraft.resources.Identifier

class RpShaders : ShaderSource {
    private val sources = HashMap<Identifier, String>()

    fun register(name: String, source: String): Identifier {
        val id = id(name)
        sources[id] = source
        return id
    }

    override fun getShader(id: Identifier, type: ShaderType): String? = sources[id]

    override fun getInclude(id: Identifier): ShaderSource.CachedIncludeSource? = null

    override fun close() = sources.clear()

    companion object {
        fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(NAMESPACE, path.lowercase().replace(Regex("[^a-z0-9/._-]"), "_"))

        const val NAMESPACE = "afterimage"
    }
}
