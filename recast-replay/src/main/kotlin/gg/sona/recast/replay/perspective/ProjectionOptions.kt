package gg.sona.recast.replay.perspective

import gg.sona.recast.protocol.PlayerAbilities


data class ProjectionOptions(
    val cameraEntityId: Int = CAMERA_ENTITY_ID,
    val cameraGameMode: Int = ADVENTURE,
    val cameraAbilities: Int = PlayerAbilities.INVULNERABLE or PlayerAbilities.FLYING or PlayerAbilities.ALLOW_FLYING,
    val maxPendingPackets: Int = 512,
    val mirrorHud: Boolean = true,
    val predictBlocks: Boolean = true,
) {
    companion object {
        const val CAMERA_ENTITY_ID = -0x52435354
        const val ADVENTURE = 2
        const val SPECTATOR = 3
        val HUD_METADATA_INDEXES = setOf(1, 17, 18)
    }
}
