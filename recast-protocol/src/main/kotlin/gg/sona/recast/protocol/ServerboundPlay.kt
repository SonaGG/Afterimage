package gg.sona.recast.protocol


object ServerboundPlay {
    const val KEEP_ALIVE = 0x00
    const val CHAT_MESSAGE = 0x01
    const val USE_ENTITY = 0x02
    const val PLAYER = 0x03
    const val PLAYER_POSITION = 0x04
    const val PLAYER_LOOK = 0x05
    const val PLAYER_POSITION_AND_LOOK = 0x06
    const val PLAYER_DIGGING = 0x07
    const val PLAYER_BLOCK_PLACEMENT = 0x08
    const val HELD_ITEM_CHANGE = 0x09
    const val ANIMATION = 0x0A
    const val ENTITY_ACTION = 0x0B
    const val STEER_VEHICLE = 0x0C
    const val CLOSE_WINDOW = 0x0D
    const val CLICK_WINDOW = 0x0E
    const val CONFIRM_TRANSACTION = 0x0F
    const val CREATIVE_INVENTORY_ACTION = 0x10
    const val ENCHANT_ITEM = 0x11
    const val UPDATE_SIGN = 0x12
    const val PLAYER_ABILITIES = 0x13
    const val TAB_COMPLETE = 0x14
    const val CLIENT_SETTINGS = 0x15
    const val CLIENT_STATUS = 0x16
    const val PLUGIN_MESSAGE = 0x17
    const val SPECTATE = 0x18
    const val RESOURCE_PACK_STATUS = 0x19
    const val COUNT = 0x1A
}
