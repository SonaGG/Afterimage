package gg.sona.afterimage.mc263.gfx

class Std140Member(val name: String, val type: String, val offset: Int)

class TranslatedFragment(val source: String, val samplers: List<Pair<String, String>>, val members: List<Std140Member>, val blockSize: Int)

object Glsl {
    const val PARAMS_BLOCK = "AfterimageParams"
    const val VIEWPORT_BLOCK = "AfterimageViewport"

    private const val HEADER = "#version 330\n#extension GL_ARB_separate_shader_objects : require\n"

    private val UNIFORM = Regex("^\\s*uniform\\s+(\\w+)\\s+(\\w+)\\s*;\\s*$")

    const val FULLSCREEN_VERTEX = HEADER + """
layout(std140) uniform AfterimageViewport {
    vec4 ViewportRect;
};
layout(location = 0) out vec2 uv;
void main() {
    vec2 unit = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4((ViewportRect.xy + unit * ViewportRect.zw) * 2.0 - 1.0, 0.0, 1.0);
    uv = unit;
}
"""

    private const val SLICED_LOOKUP = """
vec4 afterimage_sample3D(sampler2D slices, vec3 coord) {
    float size = float(textureSize(slices, 0).y);
    float z = coord.z * size - 0.5;
    float z0 = clamp(floor(z), 0.0, size - 1.0);
    float z1 = min(z0 + 1.0, size - 1.0);
    vec4 a = texture(slices, vec2((coord.x + z0) / size, coord.y));
    vec4 b = texture(slices, vec2((coord.x + z1) / size, coord.y));
    return mix(a, b, clamp(z - z0, 0.0, 1.0));
}
"""

    fun fragment(source: String): TranslatedFragment {
        val samplers = ArrayList<Pair<String, String>>()
        val scalars = ArrayList<Pair<String, String>>()
        val body = StringBuilder()
        for (line in source.lines()) {
            val match = UNIFORM.matchEntire(line)
            if (match == null) {
                body.append(line).append('\n')
                continue
            }
            val type = match.groupValues[1]
            val name = match.groupValues[2]
            if (type.startsWith("sampler")) samplers += type to name else scalars += type to name
        }
        val members = ArrayList<Std140Member>()
        var offset = 0
        val block = StringBuilder()
        if (scalars.isNotEmpty()) {
            block.append("layout(std140) uniform ").append(PARAMS_BLOCK).append(" {\n")
            for ((type, name) in scalars) {
                val alignment = alignmentOf(type)
                offset = (offset + alignment - 1) / alignment * alignment
                members += Std140Member(name, type, offset)
                block.append("    ").append(type).append(' ').append(name).append(";\n")
                offset += sizeOf(type)
            }
            block.append("};\n")
        }
        val blockSize = (offset + 15) / 16 * 16
        val header = StringBuilder(HEADER)
        for ((type, name) in samplers) header.append("uniform ").append(if (type == "sampler3D") "sampler2D" else type).append(' ').append(name).append(";\n")
        header.append(block)
        if (samplers.any { it.first == "sampler3D" }) header.append(SLICED_LOOKUP)
        var text = body.toString()
            .replace(Regex("\\bvarying\\s+vec2\\s+uv\\s*;"), "layout(location = 0) in vec2 uv;")
            .replace(Regex("\\btexture3D\\s*\\("), "afterimage_sample3D(")
            .replace(Regex("\\btexture2D\\s*\\("), "texture(")
        if (text.contains("gl_FragColor")) {
            text = "layout(location = 0) out vec4 fragColor;\n" + text.replace("gl_FragColor", "fragColor")
        }
        return TranslatedFragment(header.toString() + text, samplers, members, blockSize)
    }

    fun alignmentOf(type: String): Int = when (type) {
        "float", "int", "bool", "uint" -> 4
        "vec2", "ivec2" -> 8
        "vec3", "vec4", "ivec3", "ivec4", "mat4" -> 16
        else -> throw IllegalArgumentException("unsupported uniform type $type")
    }

    fun sizeOf(type: String): Int = when (type) {
        "float", "int", "bool", "uint" -> 4
        "vec2", "ivec2" -> 8
        "vec3", "ivec3" -> 12
        "vec4", "ivec4" -> 16
        "mat4" -> 64
        else -> throw IllegalArgumentException("unsupported uniform type $type")
    }
}
