package gg.sona.recast.mc

import gg.sona.recast.clip.Clip
import gg.sona.recast.flashback.ClipRequest

fun interface FlashbackClipListener {
    fun onFlashbackClip(request: ClipRequest, clip: Clip)
}
