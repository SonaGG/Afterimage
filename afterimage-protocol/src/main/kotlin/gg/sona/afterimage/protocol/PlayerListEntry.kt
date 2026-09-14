package gg.sona.afterimage.protocol

import java.util.*

data class PlayerListEntry(
    val uuid: UUID,
    val name: String?,
    val properties: List<ProfileProperty>,
    val gameMode: Int,
    val ping: Int,
    val displayNameJson: String?,
)
