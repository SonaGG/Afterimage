package gg.sona.afterimage.protocol


object AfterimageInternal {
    const val FIRST_ID = 0x100
    const val LOCAL_POSE = 0x100
    const val SESSION_MARK = 0x101
    const val CAMERA_FRAME = 0x102
    const val OVERLAY_RESET = 0x103
    const val LOCAL_BLOCK_BREAK = 0x104
    const val CHUNK_REF = 0x106
    const val CAMERA_FRAME_COMPACT = 0x107
    const val LOCAL_SCREEN = 0x108
    const val LOCAL_TICK = 0x109
    const val LOCAL_TARGET = 0x10A
    const val LOCAL_BLOCK_CHANGE = 0x10B
    const val LOCAL_HAND = 0x10C

    fun isInternal(packetId: Int): Boolean = packetId >= FIRST_ID
}
