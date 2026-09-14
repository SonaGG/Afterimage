package gg.sona.recast.mc

import gg.sona.recast.editor.look.LookSettings
import gg.sona.recast.render.look.CubeLut
import org.apache.logging.log4j.LogManager
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

class PostProcessor(private val lutsDirectory: () -> Path) {

    enum class Stage(val depthOfField: Boolean, val grade: Boolean) {
        DEPTH_OF_FIELD(true, false),
        GRADE(false, true),
        FULL(true, true),
    }

    class Frame(
        val look: LookSettings,
        val focusDistance: Double,
        val near: Float,
        val far: Float,
        val orthographic: Boolean,
        val seed: Float,
        val maxTaps: Int,
        val tapOffset: Float = 0f,
    )

    private class Program(val id: Int, val uniforms: Map<String, Int>) {
        operator fun get(name: String): Int = uniforms[name] ?: -1
    }

    private class LutTexture(val path: Path, val modified: Long, val id: Int, val size: Int)

    private val logger = LogManager.getLogger("Recast")
    private var look: Program? = null
    private var depth: Program? = null
    private var lut: LutTexture? = null
    var lutWarning: String? = null
        private set
    var failure: String? = null
        private set

    val available: Boolean get() = failure == null

    private fun ensure(): Boolean {
        if (failure != null) return false
        if (look != null && depth != null) return true
        try {
            look = link(VERTEX_SOURCE, LOOK_SOURCE, LOOK_UNIFORMS)
            depth = link(VERTEX_SOURCE, DEPTH_SOURCE, DEPTH_UNIFORMS)
            return true
        } catch (error: Throwable) {
            failure = error.message ?: error.toString()
            logger.warn("(Recast) post processing disabled: {}", failure)
            destroy()
            return false
        }
    }

    fun draw(stage: Stage, colorTexture: Int, depthTexture: Int, width: Int, height: Int, frame: Frame): Boolean {
        val settings = frame.look
        val grade = stage.grade && (settings.grades || settings.overlays)
        val wanted = if (stage.depthOfField && settings.depthOfField && depthTexture != 0) (settings.aperture.coerceIn(0.0, 1.0) * MAX_COC_FRACTION * height).toFloat() else 0f
        val depthOfField = wanted >= 0.5f
        val maxCoc = if (depthOfField) wanted else 0f
        if (!grade && !depthOfField) return false
        if (!ensure()) return false
        val program = look ?: return false
        val lutTexture = if (grade && settings.lut.isNotEmpty()) resolveLut(settings.lut) else null
        val taps = frame.maxTaps.coerceIn(8, MAX_TAPS)
        val radiusScale = max(MIN_RADIUS_SCALE, (maxCoc * maxCoc) / (2f * taps))
        val bias = if (radiusScale > 1f) (ln(radiusScale.toDouble()) / ln(2.0)).toFloat() else 0f
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            if (depthOfField) {
                GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR)
            } else {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
            }
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
            GL13.glActiveTexture(GL13.GL_TEXTURE1)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, if (depthOfField) depthTexture else 0)
            GL13.glActiveTexture(GL13.GL_TEXTURE2)
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, lutTexture?.id ?: 0)
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL20.glUseProgram(program.id)
            GL20.glUniform1i(program["color"], 0)
            GL20.glUniform1i(program["depth"], 1)
            GL20.glUniform1i(program["lut"], 2)
            GL20.glUniform2f(program["texel"], 1f / width, 1f / height)
            GL20.glUniform1f(program["aspect"], width.toFloat() / height)
            GL20.glUniform1f(program["near"], frame.near)
            GL20.glUniform1f(program["far"], frame.far)
            GL20.glUniform1i(program["ortho"], if (frame.orthographic) 1 else 0)
            GL20.glUniform1f(program["focus"], frame.focusDistance.toFloat())
            GL20.glUniform1f(program["focusRange"], settings.focusRange.toFloat())
            GL20.glUniform1f(program["maxCoc"], maxCoc)
            GL20.glUniform1f(program["radiusScale"], radiusScale)
            GL20.glUniform1f(program["lodBias"], bias)
            GL20.glUniform1i(program["maxTaps"], taps)
            GL20.glUniform1f(program["tapOffset"], frame.tapOffset)
            GL20.glUniform1f(program["exposure"], if (grade) 2.0.pow(settings.exposure).toFloat() else 1f)
            GL20.glUniform1f(program["contrast"], if (grade) settings.contrast.toFloat() else 1f)
            GL20.glUniform1f(program["saturation"], if (grade) settings.saturation.toFloat() else 1f)
            GL20.glUniform1f(program["lutStrength"], if (lutTexture != null) settings.lutStrength.toFloat() else 0f)
            GL20.glUniform1f(program["lutSize"], (lutTexture?.size ?: 2).toFloat())
            GL20.glUniform1f(program["vignette"], if (grade) settings.vignette.toFloat() else 0f)
            GL20.glUniform1f(program["vignetteSoftness"], settings.vignetteSoftness.toFloat())
            GL20.glUniform1f(program["letterbox"], if (grade) letterboxBar(settings.letterbox, width, height) else 0f)
            GL20.glUniform1f(program["grain"], if (grade) settings.grain.toFloat() else 0f)
            GL20.glUniform1f(program["grainSize"], max(1.0, settings.grainSize).toFloat())
            GL20.glUniform1f(program["seed"], frame.seed)
            quad()
        } finally {
            GL20.glUseProgram(0)
            GL13.glActiveTexture(GL13.GL_TEXTURE2)
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
            GL13.glActiveTexture(GL13.GL_TEXTURE1)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            GL11.glPopAttrib()
        }
        return true
    }

    fun drawDepth(
        depthTexture: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        supersample: Int,
        near: Float,
        far: Float,
        orthographic: Boolean,
        range: Float,
    ): Boolean {
        if (!ensure()) return false
        val program = depth ?: return false
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture)
            GL20.glUseProgram(program.id)
            GL20.glUniform1i(program["depth"], 0)
            GL20.glUniform2f(program["texel"], 1f / sourceWidth, 1f / sourceHeight)
            GL20.glUniform1i(program["factor"], supersample.coerceIn(1, 4))
            GL20.glUniform1f(program["near"], near)
            GL20.glUniform1f(program["far"], far)
            GL20.glUniform1i(program["ortho"], if (orthographic) 1 else 0)
            GL20.glUniform1f(program["range"], if (range > 0f) range else far)
            quad()
        } finally {
            GL20.glUseProgram(0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            GL11.glPopAttrib()
        }
        return true
    }

    private fun quad() {
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDepthMask(false)
        GL11.glDisable(GL11.GL_BLEND)
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glDisable(GL11.GL_CULL_FACE)
        GL11.glDisable(GL11.GL_LIGHTING)
        GL11.glDisable(GL11.GL_FOG)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL11.glColorMask(true, true, true, true)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(-1f, -1f)
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(1f, -1f)
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(1f, 1f)
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(-1f, 1f)
        GL11.glEnd()
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPopMatrix()
    }

    fun lutPath(name: String): Path {
        val direct = Path.of(name)
        return if (direct.isAbsolute) direct else lutsDirectory().resolve(name)
    }

    private fun resolveLut(name: String): LutTexture? {
        val path = lutPath(name)
        val modified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(-1L)
        val current = lut
        if (current != null && current.path == path && current.modified == modified) return current
        current?.let { GL11.glDeleteTextures(it.id) }
        lut = null
        if (modified < 0L) {
            lutWarning = "LUT ${path.fileName} was not found"
            return null
        }
        return try {
            val cube = CubeLut.load(path)
            val id = upload(cube)
            lutWarning = null
            LutTexture(path, modified, id, cube.size).also { lut = it }
        } catch (error: Throwable) {
            lutWarning = "LUT ${path.fileName} could not be loaded: ${error.message}"
            logger.warn("(Recast) {}", lutWarning)
            lut = LutTexture(path, modified, 0, 2)
            null
        }
    }

    private fun upload(cube: CubeLut): Int {
        val id = GL11.glGenTextures()
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, id)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE)
        val buffer = BufferUtils.createFloatBuffer(cube.data.size)
        buffer.put(cube.data).flip()
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RGB16F, cube.size, cube.size, cube.size, 0, GL11.GL_RGB, GL11.GL_FLOAT, buffer)
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
        return id
    }

    private fun link(vertex: String, fragment: String, uniforms: List<String>): Program {
        val vertexShader = compile(GL20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = compile(GL20.GL_FRAGMENT_SHADER, fragment)
        val program = GL20.glCreateProgram()
        GL20.glAttachShader(program, vertexShader)
        GL20.glAttachShader(program, fragmentShader)
        GL20.glLinkProgram(program)
        GL20.glDetachShader(program, vertexShader)
        GL20.glDetachShader(program, fragmentShader)
        GL20.glDeleteShader(vertexShader)
        GL20.glDeleteShader(fragmentShader)
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            val log = GL20.glGetProgramInfoLog(program, 4096)
            GL20.glDeleteProgram(program)
            throw IllegalStateException("shader link failed: $log")
        }
        return Program(program, uniforms.associateWith { GL20.glGetUniformLocation(program, it) })
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val log = GL20.glGetShaderInfoLog(shader, 4096)
            GL20.glDeleteShader(shader)
            throw IllegalStateException("shader compile failed: $log")
        }
        return shader
    }

    fun destroy() {
        look?.let { GL20.glDeleteProgram(it.id) }
        depth?.let { GL20.glDeleteProgram(it.id) }
        look = null
        depth = null
        lut?.let { if (it.id != 0) GL11.glDeleteTextures(it.id) }
        lut = null
    }

    companion object {
        const val NEAR_PLANE = 0.05f
        const val SQRT_2 = 1.4142135f
        const val MAX_TAPS = 512
        const val EXPORT_TAPS = 320
        const val PREVIEW_TAPS = 96
        private const val MAX_COC_FRACTION = 0.045f
        private const val MIN_RADIUS_SCALE = 0.75f

        fun farPlane(viewDistance: Int): Float = viewDistance * 16f * SQRT_2

        fun letterboxBar(aspect: Double, width: Int, height: Int): Float {
            if (aspect <= 0.0 || height <= 0) return 0f
            val frame = width.toDouble() / height
            if (aspect <= frame) return 0f
            return ((1.0 - frame / aspect) / 2.0).toFloat()
        }

        private val LOOK_UNIFORMS = listOf(
            "color", "depth", "lut", "texel", "aspect", "near", "far", "ortho", "focus", "focusRange", "maxCoc",
            "radiusScale", "lodBias", "maxTaps", "tapOffset", "exposure", "contrast", "saturation", "lutStrength", "lutSize",
            "vignette", "vignetteSoftness", "letterbox", "grain", "grainSize", "seed",
        )

        private val DEPTH_UNIFORMS = listOf("depth", "texel", "factor", "near", "far", "ortho", "range")

        private val VERTEX_SOURCE = listOf(
            "#version 120",
            "varying vec2 uv;",
            "void main() {",
            "    uv = gl_MultiTexCoord0.xy;",
            "    gl_Position = gl_Vertex;",
            "}",
        ).joinToString("\n")

        private val LOOK_SOURCE = listOf(
            "#version 120",
            "uniform sampler2D color;",
            "uniform sampler2D depth;",
            "uniform sampler3D lut;",
            "uniform vec2 texel;",
            "uniform float aspect;",
            "uniform float near;",
            "uniform float far;",
            "uniform int ortho;",
            "uniform float focus;",
            "uniform float focusRange;",
            "uniform float maxCoc;",
            "uniform float radiusScale;",
            "uniform float lodBias;",
            "uniform int maxTaps;",
            "uniform float tapOffset;",
            "uniform float exposure;",
            "uniform float contrast;",
            "uniform float saturation;",
            "uniform float lutStrength;",
            "uniform float lutSize;",
            "uniform float vignette;",
            "uniform float vignetteSoftness;",
            "uniform float letterbox;",
            "uniform float grain;",
            "uniform float grainSize;",
            "uniform float seed;",
            "varying vec2 uv;",
            "const float GOLDEN_ANGLE = 2.39996323;",
            "const int TAP_LIMIT = $MAX_TAPS;",
            "float linearDepth(vec2 at) {",
            "    float z = texture2D(depth, at).r;",
            "    if (ortho == 1) return max(0.0, (z * 2.0 - 1.0) * far);",
            "    float ndc = z * 2.0 - 1.0;",
            "    return 2.0 * near * far / (far + near - ndc * (far - near));",
            "}",
            "float cocOf(float d) {",
            "    float delta = abs(d - focus) - focusRange;",
            "    if (delta <= 0.0) return 0.0;",
            "    return min(maxCoc, maxCoc * delta / max(d, 0.05));",
            "}",
            "vec4 depthOfField() {",
            "    vec4 center = texture2D(color, uv);",
            "    if (maxCoc <= 0.5) return center;",
            "    float centerDepth = linearDepth(uv);",
            "    float centerSize = cocOf(centerDepth);",
            "    vec4 acc = center;",
            "    float total = 1.0;",
            "    float radius = radiusScale;",
            "    float angle = tapOffset;",
            "    for (int i = 0; i < TAP_LIMIT; i++) {",
            "        if (i >= maxTaps || radius >= maxCoc) break;",
            "        angle += GOLDEN_ANGLE;",
            "        vec2 at = uv + vec2(cos(angle), sin(angle)) * texel * radius;",
            "        float sampleDepth = linearDepth(at);",
            "        float sampleSize = cocOf(sampleDepth);",
            "        if (sampleDepth > centerDepth) sampleSize = clamp(sampleSize, 0.0, centerSize * 2.0);",
            "        float m = smoothstep(radius - 0.5, radius + 0.5, sampleSize);",
            "        vec4 sampleColor = texture2D(color, at, lodBias);",
            "        acc += mix(acc / total, sampleColor, m);",
            "        total += 1.0;",
            "        radius += radiusScale / radius;",
            "    }",
            "    return acc / total;",
            "}",
            "float hash(vec2 p) {",
            "    vec3 p3 = fract(vec3(p.xyx) * 0.1031);",
            "    p3 += dot(p3, p3.yzx + 33.33);",
            "    return fract((p3.x + p3.y) * p3.z);",
            "}",
            "void main() {",
            "    vec4 pixel = depthOfField();",
            "    vec3 rgb = pixel.rgb * exposure;",
            "    rgb = (rgb - 0.5) * contrast + 0.5;",
            "    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));",
            "    rgb = mix(vec3(luma), rgb, saturation);",
            "    rgb = clamp(rgb, 0.0, 1.0);",
            "    if (lutStrength > 0.0) {",
            "        vec3 graded = texture3D(lut, rgb * ((lutSize - 1.0) / lutSize) + 0.5 / lutSize).rgb;",
            "        rgb = mix(rgb, graded, lutStrength);",
            "    }",
            "    if (vignette > 0.0) {",
            "        vec2 centered = (uv - 0.5) * vec2(aspect, 1.0);",
            "        float d = length(centered) / length(vec2(0.5 * aspect, 0.5));",
            "        float mask = smoothstep(1.0 - vignetteSoftness, 1.15, d);",
            "        rgb *= 1.0 - vignette * mask;",
            "    }",
            "    if (grain > 0.0) {",
            "        vec2 cell = floor(gl_FragCoord.xy / grainSize);",
            "        float n = hash(cell + vec2(seed * 17.13, seed * 7.77)) - 0.5;",
            "        float weight = 0.35 + 0.65 * (1.0 - luma);",
            "        rgb += n * grain * 0.3 * weight;",
            "    }",
            "    float alpha = pixel.a;",
            "    if (letterbox > 0.0 && (uv.y < letterbox || uv.y > 1.0 - letterbox)) {",
            "        rgb = vec3(0.0);",
            "        alpha = 1.0;",
            "    }",
            "    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), alpha);",
            "}",
        ).joinToString("\n")

        private val DEPTH_SOURCE = listOf(
            "#version 120",
            "uniform sampler2D depth;",
            "uniform vec2 texel;",
            "uniform int factor;",
            "uniform float near;",
            "uniform float far;",
            "uniform int ortho;",
            "uniform float range;",
            "varying vec2 uv;",
            "float linearDepth(vec2 at) {",
            "    float z = texture2D(depth, at).r;",
            "    if (ortho == 1) return max(0.0, (z * 2.0 - 1.0) * far);",
            "    float ndc = z * 2.0 - 1.0;",
            "    return 2.0 * near * far / (far + near - ndc * (far - near));",
            "}",
            "void main() {",
            "    float sum = 0.0;",
            "    vec2 base = uv - texel * (float(factor) - 1.0) * 0.5;",
            "    for (int y = 0; y < 4; y++) {",
            "        if (y >= factor) break;",
            "        for (int x = 0; x < 4; x++) {",
            "            if (x >= factor) break;",
            "            sum += linearDepth(base + texel * vec2(float(x), float(y)));",
            "        }",
            "    }",
            "    float value = sum / float(factor * factor) / range;",
            "    gl_FragColor = vec4(clamp(value, 0.0, 1.0), 0.0, 0.0, 1.0);",
            "}",
        ).joinToString("\n")
    }
}
