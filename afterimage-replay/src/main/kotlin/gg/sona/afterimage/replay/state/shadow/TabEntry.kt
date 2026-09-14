package gg.sona.afterimage.replay.state.shadow

import gg.sona.afterimage.protocol.PlayerListEntry
import gg.sona.afterimage.protocol.ProfileProperty
import java.util.*

class TabEntry(val uuid: UUID, var name: String, var properties: List<ProfileProperty>) {
    var gameMode: Int = 0
    var ping: Int = 0
    var displayNameJson: String? = null

    fun toPacketEntry(): PlayerListEntry = PlayerListEntry(uuid, name, properties, gameMode, ping, displayNameJson)
}
