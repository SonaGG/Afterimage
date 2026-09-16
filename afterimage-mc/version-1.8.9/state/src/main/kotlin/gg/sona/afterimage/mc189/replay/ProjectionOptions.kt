package gg.sona.afterimage.mc189.replay

import gg.sona.afterimage.protocol.MetadataEntry
import gg.sona.afterimage.protocol.PlayerAbilities


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
        const val CAMERA_FLAG_MASK = 0x01 or 0x20

        fun cameraMetadata(entries: List<MetadataEntry>): List<MetadataEntry> =
            entries.mapNotNull { entry ->
                when {
                    entry.index in HUD_METADATA_INDEXES -> entry
                    entry.index == 0 && entry.type == MetadataEntry.BYTE ->
                        MetadataEntry.ofByte(0, entry.byteValue() and CAMERA_FLAG_MASK)

                    else -> null
                }
            }
    }
}
