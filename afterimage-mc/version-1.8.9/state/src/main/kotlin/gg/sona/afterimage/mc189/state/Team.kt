package gg.sona.afterimage.mc189.state

import gg.sona.afterimage.protocol.Teams
import gg.sona.afterimage.world.TeamState


class Team(override val name: String) : TeamState {
    var displayName: String = name
    override var prefix: String = ""
    override var suffix: String = ""
    var friendlyFire: Int = 0
    var nameTagVisibility: String = "always"
    var color: Int = -1
    override val members = LinkedHashSet<String>()

    fun toPacket(mode: Int, players: List<String> = members.toList()): Teams =
        Teams(name, mode, displayName, prefix, suffix, friendlyFire, nameTagVisibility, color, players)
}
