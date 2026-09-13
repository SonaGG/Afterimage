package gg.sona.recast.editor

import gg.sona.recast.clip.Clip
import gg.sona.recast.flashback.ClipRequest
import java.util.*

data class InboxEntry(val id: UUID, val request: ClipRequest, val clip: Clip, val receivedAtEpochMillis: Long)
