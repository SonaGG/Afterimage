package gg.sona.afterimage.mc189.state

import gg.sona.afterimage.protocol.PlayerListEntry
import gg.sona.afterimage.protocol.ProfileProperty
import gg.sona.afterimage.world.PlayerProfile
import java.util.*

class TabEntry(override val uuid: UUID, override var name: String, var properties: List<ProfileProperty>) : PlayerProfile {
    override var gameMode: Int = 0
    override var ping: Int = 0
    override var displayNameJson: String? = null

    fun toPacketEntry(): PlayerListEntry = PlayerListEntry(uuid, name, properties, gameMode, ping, displayNameJson)
}
