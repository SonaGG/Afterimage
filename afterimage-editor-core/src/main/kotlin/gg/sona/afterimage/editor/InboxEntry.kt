package gg.sona.afterimage.editor

import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.flashback.ClipRequest
import java.util.*

data class InboxEntry(val id: UUID, val request: ClipRequest, val clip: Clip, val receivedAtEpochMillis: Long)
