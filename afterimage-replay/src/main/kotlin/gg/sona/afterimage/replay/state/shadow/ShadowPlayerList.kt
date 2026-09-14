package gg.sona.afterimage.replay.state.shadow

import gg.sona.afterimage.protocol.PlayerListItem
import java.util.*

class ShadowPlayerList {
    val entries = LinkedHashMap<UUID, TabEntry>()
    val knownProfiles = HashMap<UUID, TabEntry>()
    var headerJson: String? = null
    var footerJson: String? = null

    fun apply(packet: PlayerListItem) {
        for (entry in packet.entries) {
            when (packet.action) {
                PlayerListItem.ADD_PLAYER -> {
                    val tab = TabEntry(entry.uuid, entry.name ?: "", entry.properties)
                    tab.gameMode = entry.gameMode
                    tab.ping = entry.ping
                    tab.displayNameJson = entry.displayNameJson
                    entries[entry.uuid] = tab
                    knownProfiles[entry.uuid] = tab
                }

                PlayerListItem.UPDATE_GAME_MODE -> entries[entry.uuid]?.gameMode = entry.gameMode
                PlayerListItem.UPDATE_LATENCY -> entries[entry.uuid]?.ping = entry.ping
                PlayerListItem.UPDATE_DISPLAY_NAME -> entries[entry.uuid]?.displayNameJson = entry.displayNameJson
                PlayerListItem.REMOVE_PLAYER -> entries.remove(entry.uuid)
            }
        }
    }

    fun profile(uuid: UUID): TabEntry? = entries[uuid] ?: knownProfiles[uuid]

    fun clear() {
        entries.clear()
        headerJson = null
        footerJson = null
    }
}
