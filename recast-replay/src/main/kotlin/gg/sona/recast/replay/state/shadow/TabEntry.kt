package gg.sona.recast.replay.state.shadow

import gg.sona.recast.protocol.PlayerListEntry
import gg.sona.recast.protocol.ProfileProperty
import java.util.*

class TabEntry(val uuid: UUID, var name: String, var properties: List<ProfileProperty>) {
    var gameMode: Int = 0
    var ping: Int = 0
    var displayNameJson: String? = null

    fun toPacketEntry(): PlayerListEntry = PlayerListEntry(uuid, name, properties, gameMode, ping, displayNameJson)
}
