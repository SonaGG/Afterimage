package gg.sona.recast.editor.host

import gg.sona.recast.camera.CameraPose
import java.nio.file.Path

interface EditorHost {
    val camera: CameraControl
    val visuals: VisualSettings
    val gizmos: GizmoBatch

    fun pickEntity(normalizedX: Float, normalizedY: Float): ViewportPick?

    fun entityModel(entityId: Int): DoubleArray?

    fun project(x: Double, y: Double, z: Double): FloatArray?

    fun ray(normalizedX: Float, normalizedY: Float): DoubleArray?

    fun worldTimeOfDay(): Long?

    fun requestPreview(pose: CameraPose?, width: Int, height: Int)

    fun previewTexture(): IntArray?

    fun thumbnail(recording: Path): Int?

    var uiScale: Float

    fun captureThumbnail(recording: Path)
    val recording: RecordingControl
    val replay: ReplayControl
    val projectsDirectory: Path
    val bakesDirectory: Path
    val exportsDirectory: Path
    val lutsDirectory: Path

    fun resourcePacks(): List<String>

    fun activeResourcePacks(): List<String>

    fun message(text: String)

    fun preference(key: String): String?

    fun setPreference(key: String, value: String)

    fun later(action: () -> Unit)

    fun keybindings(): List<KeybindInfo>

    fun captureNextKeyDown(): Int?

    fun setKeybinding(name: String, keyCode: Int)

    fun resetKeybinding(name: String)
}
