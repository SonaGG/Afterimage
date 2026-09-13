package gg.sona.recast.replay.state.shadow

import gg.sona.recast.protocol.Teams


class Team(val name: String) {
    var displayName: String = name
    var prefix: String = ""
    var suffix: String = ""
    var friendlyFire: Int = 0
    var nameTagVisibility: String = "always"
    var color: Int = -1
    val members = LinkedHashSet<String>()

    fun toPacket(mode: Int, players: List<String> = members.toList()): Teams =
        Teams(name, mode, displayName, prefix, suffix, friendlyFire, nameTagVisibility, color, players)
}
