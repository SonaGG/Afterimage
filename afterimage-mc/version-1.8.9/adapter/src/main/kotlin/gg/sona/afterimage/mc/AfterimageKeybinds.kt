package gg.sona.afterimage.mc

import net.minecraft.client.Minecraft
import net.minecraft.client.options.KeyBinding
import org.lwjgl.input.Keyboard

class AfterimageKeybinds(private val minecraft: Minecraft) {

    val toggleRecording = KeyBinding("key.afterimage.record", Keyboard.KEY_F6, CATEGORY)
    val marker = KeyBinding("key.afterimage.marker", Keyboard.KEY_F7, CATEGORY)
    val workspace = KeyBinding("key.afterimage.workspace", Keyboard.KEY_F8, CATEGORY)
    val playPause = KeyBinding("key.afterimage.play", Keyboard.KEY_P, CATEGORY)
    val seekBack = KeyBinding("key.afterimage.seek_back", Keyboard.KEY_LBRACKET, CATEGORY)
    val seekForward = KeyBinding("key.afterimage.seek_forward", Keyboard.KEY_RBRACKET, CATEGORY)
    val slower = KeyBinding("key.afterimage.slower", Keyboard.KEY_COMMA, CATEGORY)
    val faster = KeyBinding("key.afterimage.faster", Keyboard.KEY_PERIOD, CATEGORY)
    val cameraMode = KeyBinding("key.afterimage.camera_mode", Keyboard.KEY_C, CATEGORY)
    val keyframe = KeyBinding("key.afterimage.keyframe", Keyboard.KEY_K, CATEGORY)
    val instantClip = KeyBinding("key.afterimage.instant_clip", Keyboard.KEY_F9, CATEGORY)

    val all: List<KeyBinding> = listOf(
        toggleRecording,
        marker,
        workspace,
        playPause,
        seekBack,
        seekForward,
        slower,
        faster,
        cameraMode,
        keyframe,
        instantClip
    )

    val labels: Map<String, String> = mapOf(
        toggleRecording.name to "Toggle recording",
        marker.name to "Add marker",
        workspace.name to "Toggle workspace",
        playPause.name to "Play / pause",
        seekBack.name to "Seek back",
        seekForward.name to "Seek forward",
        slower.name to "Playback slower",
        faster.name to "Playback faster",
        cameraMode.name to "Cycle camera mode",
        keyframe.name to "Add camera keyframe",
        instantClip.name to "Instant clip",
    )

    fun register() {
        val options = minecraft.options
        val existing = options.keyBindings.toList()
        val missing = all.filter { binding -> existing.none { it.name == binding.name } }
        if (missing.isEmpty()) return
        options.keyBindings = (existing + missing).toTypedArray()
        KeyBinding.resetMapping()
    }

    companion object {
        const val CATEGORY = "key.categories.afterimage"
    }
}
