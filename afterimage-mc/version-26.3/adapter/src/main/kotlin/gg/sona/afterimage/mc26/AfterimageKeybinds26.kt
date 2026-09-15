package gg.sona.afterimage.mc26

import gg.sona.afterimage.mc26.mixin.OptionsAccessor
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.lwjgl.sdl.SDLScancode

class AfterimageKeybinds26(private val minecraft: Minecraft) {
    val toggleRecording = KeyMapping("key.afterimage.record", SDLScancode.SDL_SCANCODE_F6, CATEGORY)
    val marker = KeyMapping("key.afterimage.marker", SDLScancode.SDL_SCANCODE_F7, CATEGORY)
    val workspace = KeyMapping("key.afterimage.workspace", SDLScancode.SDL_SCANCODE_F8, CATEGORY)
    val playPause = KeyMapping("key.afterimage.play", SDLScancode.SDL_SCANCODE_P, CATEGORY)
    val seekBack = KeyMapping("key.afterimage.seek_back", SDLScancode.SDL_SCANCODE_LEFTBRACKET, CATEGORY)
    val seekForward = KeyMapping("key.afterimage.seek_forward", SDLScancode.SDL_SCANCODE_RIGHTBRACKET, CATEGORY)
    val slower = KeyMapping("key.afterimage.slower", SDLScancode.SDL_SCANCODE_COMMA, CATEGORY)
    val faster = KeyMapping("key.afterimage.faster", SDLScancode.SDL_SCANCODE_PERIOD, CATEGORY)
    val cameraMode = KeyMapping("key.afterimage.camera_mode", SDLScancode.SDL_SCANCODE_C, CATEGORY)
    val keyframe = KeyMapping("key.afterimage.keyframe", SDLScancode.SDL_SCANCODE_K, CATEGORY)
    val instantClip = KeyMapping("key.afterimage.instant_clip", SDLScancode.SDL_SCANCODE_F9, CATEGORY)

    val all: List<KeyMapping> = listOf(toggleRecording, marker, workspace, playPause, seekBack, seekForward, slower, faster, cameraMode, keyframe, instantClip)

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
        val existing = options.keyMappings.toList()
        val missing = all.filter { binding -> existing.none { it.name == binding.name } }
        if (missing.isEmpty()) return
        (options as OptionsAccessor).afterimage_setKeyMappings((existing + missing).toTypedArray())
        KeyMapping.resetMapping()
    }

    companion object {
        val CATEGORY: KeyMapping.Category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("afterimage", "afterimage"))
    }
}
