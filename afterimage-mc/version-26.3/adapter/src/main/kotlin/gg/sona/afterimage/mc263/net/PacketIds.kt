package gg.sona.afterimage.mc263.net

import net.minecraft.SharedConstants
import net.minecraft.network.ProtocolInfo
import net.minecraft.network.protocol.PacketType
import net.minecraft.network.protocol.game.GamePacketTypes
import net.minecraft.network.protocol.game.GameProtocols

object PacketIds {
    val protocolVersion: Int by lazy { SharedConstants.getCurrentVersion().protocolVersion() }

    private val clientbound: Map<PacketType<*>, Int> by lazy { collect(GameProtocols.CLIENTBOUND_TEMPLATE.details()) }
    private val serverbound: Map<PacketType<*>, Int> by lazy { collect(GameProtocols.SERVERBOUND_TEMPLATE.details()) }

    private fun collect(details: ProtocolInfo.Details): Map<PacketType<*>, Int> {
        val result = HashMap<PacketType<*>, Int>()
        details.listPackets { type, id -> result[type] = id }
        return result
    }

    fun clientbound(type: PacketType<*>): Int = clientbound[type] ?: -1

    fun serverbound(type: PacketType<*>): Int = serverbound[type] ?: -1

    val LOGIN: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_LOGIN) }
    val RESPAWN: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_RESPAWN) }
    val LEVEL_CHUNK_WITH_LIGHT: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT) }
    val FORGET_LEVEL_CHUNK: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_FORGET_LEVEL_CHUNK) }
    val ADD_ENTITY: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_ADD_ENTITY) }
    val REMOVE_ENTITIES: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_REMOVE_ENTITIES) }
    val ENTITY_POSITION_SYNC: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_ENTITY_POSITION_SYNC) }
    val TELEPORT_ENTITY: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_TELEPORT_ENTITY) }
    val PLAYER_INFO_UPDATE: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE) }
    val PLAYER_INFO_REMOVE: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_REMOVE) }
    val SET_CHUNK_CACHE_CENTER: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_SET_CHUNK_CACHE_CENTER) }
    val SET_CHUNK_CACHE_RADIUS: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_SET_CHUNK_CACHE_RADIUS) }
    val GAME_EVENT: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_GAME_EVENT) }
    val SET_TIME: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_SET_TIME) }
    val SET_DEFAULT_SPAWN_POSITION: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_SET_DEFAULT_SPAWN_POSITION) }
    val PLAYER_ABILITIES: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_PLAYER_ABILITIES) }
    val CHANGE_DIFFICULTY: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_CHANGE_DIFFICULTY) }
    val BUNDLE_DELIMITER: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_BUNDLE_DELIMITER) }
    val PLAYER_CHAT: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_PLAYER_CHAT) }
    val DELETE_CHAT: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_DELETE_CHAT) }
    val SET_OBJECTIVE: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_SET_OBJECTIVE) }
    val SET_PLAYER_TEAM: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_SET_PLAYER_TEAM) }
    val SET_SCORE: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_SET_SCORE) }
    val RESET_SCORE: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_RESET_SCORE) }
    val START_CONFIGURATION: Int by lazy { clientbound(GamePacketTypes.CLIENTBOUND_START_CONFIGURATION) }

    const val CONFIGURATION_FLAG = 0x10000

    fun isConfiguration(packetId: Int): Boolean = packetId and CONFIGURATION_FLAG != 0

    fun wireId(packetId: Int): Int = packetId and CONFIGURATION_FLAG.inv()

    val TRANSIENT: Set<Int> by lazy {
        ids(
            GamePacketTypes.CLIENTBOUND_SYSTEM_CHAT,
            GamePacketTypes.CLIENTBOUND_PLAYER_CHAT,
            GamePacketTypes.CLIENTBOUND_SET_TITLE_TEXT,
            GamePacketTypes.CLIENTBOUND_SET_SUBTITLE_TEXT,
            GamePacketTypes.CLIENTBOUND_SET_ACTION_BAR_TEXT,
            GamePacketTypes.CLIENTBOUND_TAKE_ITEM_ENTITY,
            GamePacketTypes.CLIENTBOUND_EXPLODE,
            GamePacketTypes.CLIENTBOUND_LEVEL_EVENT,
            GamePacketTypes.CLIENTBOUND_SOUND,
            GamePacketTypes.CLIENTBOUND_SOUND_ENTITY,
            GamePacketTypes.CLIENTBOUND_LEVEL_PARTICLES,
            GamePacketTypes.CLIENTBOUND_ENTITY_EVENT,
            GamePacketTypes.CLIENTBOUND_DAMAGE_EVENT,
            GamePacketTypes.CLIENTBOUND_HURT_ANIMATION,
            GamePacketTypes.CLIENTBOUND_ANIMATE,
            GamePacketTypes.CLIENTBOUND_SWING_ANIMATION,
            GamePacketTypes.CLIENTBOUND_DISGUISED_CHAT,
            GamePacketTypes.CLIENTBOUND_DELETE_CHAT,
            GamePacketTypes.CLIENTBOUND_SET_TITLES_ANIMATION,
            GamePacketTypes.CLIENTBOUND_CLEAR_TITLES,
            GamePacketTypes.CLIENTBOUND_ADD_TRANSIENT_BLOCK,
            GamePacketTypes.CLIENTBOUND_BLOCK_DESTRUCTION,
            GamePacketTypes.CLIENTBOUND_BLOCK_EVENT,
            GamePacketTypes.CLIENTBOUND_PLAYER_COMBAT_KILL,
            GamePacketTypes.CLIENTBOUND_PLAYER_COMBAT_ENTER,
            GamePacketTypes.CLIENTBOUND_PLAYER_COMBAT_END,
        )
    }

    val VISUAL_EVENTS: Set<Int> by lazy {
        ids(
            GamePacketTypes.CLIENTBOUND_LEVEL_PARTICLES,
            GamePacketTypes.CLIENTBOUND_EXPLODE,
            GamePacketTypes.CLIENTBOUND_TAKE_ITEM_ENTITY,
            GamePacketTypes.CLIENTBOUND_LEVEL_EVENT,
            GamePacketTypes.CLIENTBOUND_ANIMATE,
            GamePacketTypes.CLIENTBOUND_ENTITY_EVENT,
            GamePacketTypes.CLIENTBOUND_HURT_ANIMATION,
            GamePacketTypes.CLIENTBOUND_SWING_ANIMATION,
            GamePacketTypes.CLIENTBOUND_ADD_TRANSIENT_BLOCK,
            GamePacketTypes.CLIENTBOUND_BLOCK_EVENT,
        )
    }

    private fun ids(vararg types: PacketType<*>): Set<Int> = types.map { clientbound(it) }.filter { it >= 0 }.toSet()
}
