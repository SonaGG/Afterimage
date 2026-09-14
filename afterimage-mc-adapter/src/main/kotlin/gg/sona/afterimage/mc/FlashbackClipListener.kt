package gg.sona.afterimage.mc

import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.flashback.ClipRequest

fun interface FlashbackClipListener {
    fun onFlashbackClip(request: ClipRequest, clip: Clip)
}
