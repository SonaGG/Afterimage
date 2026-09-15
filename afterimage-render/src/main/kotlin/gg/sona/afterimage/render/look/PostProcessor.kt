package gg.sona.afterimage.render.look

import gg.sona.afterimage.editor.look.LookSettings
import gg.sona.afterimage.gfx.Filter
import gg.sona.afterimage.gfx.FullscreenPass
import gg.sona.afterimage.gfx.Gfx
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.gfx.Texture
import gg.sona.afterimage.gfx.Texture3D
import gg.sona.afterimage.gfx.Viewport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class PostProcessor(private val gfx: Gfx, private val lutsDirectory: () -> Path, private val warn: (String, Throwable?) -> Unit) {

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
        val tanHalfFov: FloatArray,
        val seed: Float,
        val maxTaps: Int,
        val spacing: Float,
        val tapOffset: Float = 0f,
    )

    private class LutTexture(val path: Path, val modified: Long, val texture: Texture3D?, val size: Int)

    private var look: FullscreenPass? = null
    private var depth: FullscreenPass? = null
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
            look = gfx.createPass(LOOK_SOURCE, LOOK_UNIFORMS)
            depth = gfx.createPass(DEPTH_SOURCE, DEPTH_UNIFORMS)
            return true
        } catch (error: Throwable) {
            failure = error.message ?: error.toString()
            warn("post processing disabled: $failure", null)
            destroy()
            return false
        }
    }

    fun draw(stage: Stage, target: Target?, viewport: Viewport, color: Texture, depthTexture: Texture?, frame: Frame): Boolean {
        val settings = frame.look
        val width = viewport.width
        val height = viewport.height
        val grade = stage.grade && (settings.grades || settings.overlays)
        val wanted = if (stage.depthOfField && settings.depthOfField && depthTexture != null) (settings.aperture.coerceIn(0.0, 1.0) * MAX_COC_FRACTION * height).toFloat() else 0f
        val depthOfField = wanted >= 0.5f
        val maxCoc = if (depthOfField) wanted else 0f
        if (!grade && !depthOfField) return false
        if (!ensure()) return false
        val pass = look ?: return false
        val lutTexture = if (grade && settings.lut.isNotEmpty()) resolveLut(settings.lut) else null
        val taps = if (depthOfField) (PI * maxCoc * maxCoc / (frame.spacing * frame.spacing)).toInt().coerceIn(MIN_TAPS, frame.maxTaps.coerceIn(MIN_TAPS, MAX_TAPS)) else MIN_TAPS
        val spacing = if (depthOfField) maxCoc * sqrt(PI / taps) else 1f
        val bias = if (spacing > LOD_TEXELS) min(MAX_LOD_BIAS, (ln((spacing / LOD_TEXELS).toDouble()) / ln(2.0)).toFloat()) else 0f
        if (depthOfField) {
            color.generateMipmaps()
            color.setFilter(Filter.LINEAR_MIPMAP)
        } else {
            color.setFilter(Filter.NEAREST)
        }
        pass.draw(target, viewport) {
            texture("color", 0, color)
            texture("depth", 1, if (depthOfField) depthTexture else null)
            texture3D("lut", 2, lutTexture?.texture)
            vec2("texel", 1f / width, 1f / height)
            float("aspect", width.toFloat() / height)
            float("near", frame.near)
            float("far", frame.far)
            int("ortho", if (frame.orthographic) 1 else 0)
            float("focus", frame.focusDistance.toFloat())
            float("focusRange", settings.focusRange.toFloat())
            float("maxCoc", maxCoc)
            float("tapSpacing", spacing)
            vec2("tanHalf", frame.tanHalfFov[0], frame.tanHalfFov[1])
            float("lodBias", bias)
            int("maxTaps", taps)
            float("tapOffset", frame.tapOffset)
            float("exposure", if (grade) 2.0.pow(settings.exposure).toFloat() else 1f)
            float("contrast", if (grade) settings.contrast.toFloat() else 1f)
            float("saturation", if (grade) settings.saturation.toFloat() else 1f)
            float("lutStrength", if (lutTexture?.texture != null) settings.lutStrength.toFloat() else 0f)
            float("lutSize", (lutTexture?.size ?: 2).toFloat())
            float("vignette", if (grade) settings.vignette.toFloat() else 0f)
            float("vignetteSoftness", settings.vignetteSoftness.toFloat())
            float("letterbox", if (grade) letterboxBar(settings.letterbox, width, height) else 0f)
            float("grain", if (grade) settings.grain.toFloat() else 0f)
            float("grainSize", max(1.0, settings.grainSize).toFloat())
            float("seed", frame.seed)
        }
        return true
    }

    fun drawDepth(
        target: Target?,
        viewport: Viewport,
        depthTexture: Texture,
        sourceWidth: Int,
        sourceHeight: Int,
        supersample: Int,
        near: Float,
        far: Float,
        orthographic: Boolean,
        range: Float,
    ): Boolean {
        if (!ensure()) return false
        val pass = depth ?: return false
        pass.draw(target, viewport) {
            texture("depth", 0, depthTexture)
            vec2("texel", 1f / sourceWidth, 1f / sourceHeight)
            int("factor", supersample.coerceIn(1, 4))
            float("near", near)
            float("far", far)
            int("ortho", if (orthographic) 1 else 0)
            float("range", if (range > 0f) range else far)
        }
        return true
    }

    fun lutPath(name: String): Path {
        val direct = Path.of(name)
        return if (direct.isAbsolute) direct else lutsDirectory().resolve(name)
    }

    private fun resolveLut(name: String): LutTexture? {
        val path = lutPath(name)
        val modified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(-1L)
        val current = lut
        if (current != null && current.path == path && current.modified == modified) return current.takeIf { it.texture != null }
        current?.texture?.close()
        lut = null
        if (modified < 0L) {
            lutWarning = "LUT ${path.fileName} was not found"
            return null
        }
        return try {
            val cube = CubeLut.load(path)
            val texture = gfx.createTexture3D(cube.size, cube.data)
            lutWarning = null
            LutTexture(path, modified, texture, cube.size).also { lut = it }
        } catch (error: Throwable) {
            lutWarning = "LUT ${path.fileName} could not be loaded: ${error.message}"
            warn(lutWarning!!, error)
            lut = LutTexture(path, modified, null, 2)
            null
        }
    }

    fun destroy() {
        look?.close()
        depth?.close()
        look = null
        depth = null
        lut?.texture?.close()
        lut = null
    }

    companion object {
        const val NEAR_PLANE = 0.05f
        const val SQRT_2 = 1.4142135f
        const val MAX_TAPS = 2048
        const val MIN_TAPS = 16
        const val EXPORT_TAPS = 2048
        const val EXPORT_SPACING = 1.75f
        const val PREVIEW_TAPS = 512
        const val PREVIEW_SPACING = 2.5f
        private const val MAX_COC_FRACTION = 0.045f
        private const val LOD_TEXELS = 1.5f
        private const val MAX_LOD_BIAS = 4f
        private const val PI = 3.1415927f

        fun farPlane(viewDistance: Int): Float = viewDistance * 16f * SQRT_2

        fun letterboxBar(aspect: Double, width: Int, height: Int): Float {
            if (aspect <= 0.0 || height <= 0) return 0f
            val frame = width.toDouble() / height
            if (aspect <= frame) return 0f
            return ((1.0 - frame / aspect) / 2.0).toFloat()
        }

        private val LOOK_UNIFORMS = listOf(
            "color", "depth", "lut", "texel", "aspect", "near", "far", "ortho", "focus", "focusRange", "maxCoc",
            "tapSpacing", "tanHalf", "lodBias", "maxTaps", "tapOffset", "exposure", "contrast", "saturation", "lutStrength", "lutSize",
            "vignette", "vignetteSoftness", "letterbox", "grain", "grainSize", "seed",
        )

        private val DEPTH_UNIFORMS = listOf("depth", "texel", "factor", "near", "far", "ortho", "range")

        private val LOOK_SOURCE = """
uniform sampler2D color;
uniform sampler2D depth;
uniform sampler3D lut;
uniform vec2 texel;
uniform float aspect;
uniform float near;
uniform float far;
uniform int ortho;
uniform float focus;
uniform float focusRange;
uniform float maxCoc;
uniform float tapSpacing;
uniform vec2 tanHalf;
uniform float lodBias;
uniform int maxTaps;
uniform float tapOffset;
uniform float exposure;
uniform float contrast;
uniform float saturation;
uniform float lutStrength;
uniform float lutSize;
uniform float vignette;
uniform float vignetteSoftness;
uniform float letterbox;
uniform float grain;
uniform float grainSize;
uniform float seed;
varying vec2 uv;
const float GOLDEN_ANGLE = 2.39996323;
const mat2 GOLDEN_ROTATION = mat2(-0.7373688, 0.6754903, -0.6754903, -0.7373688);
const int TAP_LIMIT = $MAX_TAPS;
float linearDepth(vec2 at) {
    float z = texture2D(depth, at).r;
    if (ortho == 1) return max(0.0, (z * 2.0 - 1.0) * far);
    float ndc = z * 2.0 - 1.0;
    return 2.0 * near * far / (far + near - ndc * (far - near));
}
float viewDistance(vec2 at) {
    float z = linearDepth(at);
    if (ortho == 1) return z;
    vec2 v = (at * 2.0 - 1.0) * tanHalf;
    return z * sqrt(1.0 + dot(v, v));
}
float cocOf(float d) {
    float delta = abs(d - focus) - focusRange;
    if (delta <= 0.0) return 0.0;
    return min(maxCoc, maxCoc * delta / max(d, 0.05));
}
vec3 toLinear(vec3 c) {
    return c * c;
}
vec4 depthOfField() {
    vec4 center = texture2D(color, uv);
    if (maxCoc <= 0.5) return center;
    float centerDepth = viewDistance(uv);
    float centerSize = cocOf(centerDepth);
    vec3 acc = toLinear(center.rgb);
    float accAlpha = center.a;
    float total = 1.0;
    float invTaps = 1.0 / float(maxTaps);
    vec2 direction = vec2(cos(tapOffset), sin(tapOffset));
    for (int i = 0; i < TAP_LIMIT; i++) {
        if (i >= maxTaps) break;
        float radius = maxCoc * sqrt((float(i) + 0.5) * invTaps);
        direction = GOLDEN_ROTATION * direction;
        vec2 at = uv + direction * texel * radius;
        float sampleDepth = viewDistance(at);
        float sampleSize = cocOf(sampleDepth);
        if (sampleDepth > centerDepth) sampleSize = clamp(sampleSize, 0.0, centerSize * 2.0);
        float m = smoothstep(max(radius - tapSpacing * 0.5, 0.0), radius + 0.5, sampleSize);
        vec4 s = texture2D(color, at, lodBias);
        acc += mix(acc / total, toLinear(s.rgb), m);
        accAlpha += mix(accAlpha / total, s.a, m);
        total += 1.0;
    }
    return vec4(sqrt(acc / total), accAlpha / total);
}
float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
void main() {
    vec4 pixel = depthOfField();
    vec3 rgb = pixel.rgb * exposure;
    rgb = (rgb - 0.5) * contrast + 0.5;
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    rgb = mix(vec3(luma), rgb, saturation);
    rgb = clamp(rgb, 0.0, 1.0);
    if (lutStrength > 0.0) {
        vec3 graded = texture3D(lut, rgb * ((lutSize - 1.0) / lutSize) + 0.5 / lutSize).rgb;
        rgb = mix(rgb, graded, lutStrength);
    }
    if (vignette > 0.0) {
        vec2 centered = (uv - 0.5) * vec2(aspect, 1.0);
        float d = length(centered) / length(vec2(0.5 * aspect, 0.5));
        float mask = smoothstep(1.0 - vignetteSoftness, 1.15, d);
        rgb *= 1.0 - vignette * mask;
    }
    if (grain > 0.0) {
        vec2 cell = floor(gl_FragCoord.xy / grainSize);
        float n = hash(cell + vec2(seed * 17.13, seed * 7.77)) - 0.5;
        float weight = 0.35 + 0.65 * (1.0 - luma);
        rgb += n * grain * 0.3 * weight;
    }
    float alpha = pixel.a;
    if (letterbox > 0.0 && (uv.y < letterbox || uv.y > 1.0 - letterbox)) {
        rgb = vec3(0.0);
        alpha = 1.0;
    }
    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), alpha);
}
"""

        private val DEPTH_SOURCE = """
uniform sampler2D depth;
uniform vec2 texel;
uniform int factor;
uniform float near;
uniform float far;
uniform int ortho;
uniform float range;
varying vec2 uv;
float linearDepth(vec2 at) {
    float z = texture2D(depth, at).r;
    if (ortho == 1) return max(0.0, (z * 2.0 - 1.0) * far);
    float ndc = z * 2.0 - 1.0;
    return 2.0 * near * far / (far + near - ndc * (far - near));
}
void main() {
    float sum = 0.0;
    vec2 base = uv - texel * (float(factor) - 1.0) * 0.5;
    for (int y = 0; y < 4; y++) {
        if (y >= factor) break;
        for (int x = 0; x < 4; x++) {
            if (x >= factor) break;
            sum += linearDepth(base + texel * vec2(float(x), float(y)));
        }
    }
    float value = sum / float(factor * factor) / range;
    gl_FragColor = vec4(clamp(value, 0.0, 1.0), 0.0, 0.0, 1.0);
}
"""
    }
}
