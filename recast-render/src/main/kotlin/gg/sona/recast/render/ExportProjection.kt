package gg.sona.recast.render


enum class ExportProjection(val label: String, val passes: Int) {
    PERSPECTIVE("Perspective", 1),
    STEREO_SBS("Stereo side-by-side", 2),
    CUBE_MAP("Cube map", 6),
    EQUIRECTANGULAR("360 equirectangular", 6),
    ORTHOGRAPHIC("Orthographic", 1),
}
