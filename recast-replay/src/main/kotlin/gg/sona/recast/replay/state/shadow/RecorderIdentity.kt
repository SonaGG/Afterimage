package gg.sona.recast.replay.state.shadow

import java.util.*

data class RecorderIdentity(val uuid: UUID?, val name: String?) {
    companion object {
        val UNKNOWN = RecorderIdentity(null, null)
    }
}
