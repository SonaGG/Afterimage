package gg.sona.recast.format


object RecastFormat {
    const val EXTENSION = "recast"
    const val VERSION = 2

    val FILE_MAGIC = byteArrayOf('R'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte())
    val SEGMENT_MAGIC = byteArrayOf('S'.code.toByte(), 'E'.code.toByte(), 'G'.code.toByte(), 'M'.code.toByte())
    val INDEX_MAGIC = byteArrayOf('I'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(), 'X'.code.toByte())
    val TRAILER_MAGIC = byteArrayOf('R'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), 'X'.code.toByte())
    val BLOB_MAGIC = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'O'.code.toByte(), 'B'.code.toByte())

    const val SEGMENT_HEADER_BYTES = 4 + 1 + 1 + 8 + 8 + 4 + 4 + 4 + 4
    const val TRAILER_BYTES = 8 + 4
    const val INDEX_ENTRY_BYTES = 8 + 1 + 8 + 8 + 4 + 4 + 4
    const val BLOB_ENTRY_BYTES = 8 + 4 + 4 + 4

    object MetadataKeys {
        const val PLAYER_NAME = "player.name"
        const val PLAYER_UUID = "player.uuid"
        const val SERVER_ADDRESS = "server.address"
        const val MINECRAFT_VERSION = "minecraft.version"
        const val RECAST_VERSION = "recast.version"
        const val TITLE = "title"
    }
}
