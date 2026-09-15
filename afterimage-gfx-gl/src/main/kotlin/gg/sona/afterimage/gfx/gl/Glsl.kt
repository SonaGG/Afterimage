package gg.sona.afterimage.gfx.gl

object Glsl {
    fun vertex(source: String, version: Int): String = translate(source, version, vertex = true)

    fun fragment(source: String, version: Int): String = translate(source, version, vertex = false)

    private fun translate(source: String, version: Int, vertex: Boolean): String {
        val header = if (version >= 150) "#version $version core" else "#version $version"
        if (version < 130) return header + "\n" + source
        var body = source
            .replace(Regex("\\battribute\\b"), "in")
            .replace(Regex("\\bvarying\\b"), if (vertex) "out" else "in")
            .replace(Regex("\\btexture(2D|3D)\\s*\\("), "texture(")
        if (!vertex && body.contains("gl_FragColor")) {
            body = "out vec4 fragColor;\n" + body.replace("gl_FragColor", "fragColor")
        }
        return header + "\n" + body
    }
}
