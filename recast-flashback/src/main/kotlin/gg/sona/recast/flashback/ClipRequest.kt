package gg.sona.recast.flashback

import gg.sona.recast.clip.Clip
import gg.sona.recast.clip.ClipOrigin
import java.nio.file.Path
import java.util.*

data class ClipRequest(val trigger: Trigger, val profile: GameProfile) {

    val preRollNanos: Long get() = trigger.preRollNanos ?: profile.preRollNanos

    val postRollNanos: Long get() = trigger.postRollNanos ?: profile.postRollNanos

    fun toClip(recording: Path, sessionId: UUID, recordingOffsetNanos: Long = 0L): Clip =
        Clip.around(
            recording,
            sessionId,
            trigger.nanos - recordingOffsetNanos,
            preRollNanos,
            postRollNanos,
            trigger.title,
            ClipOrigin.FLASHBACK
        )
            .tagged(*trigger.tags.toTypedArray(), profile.id)
}
