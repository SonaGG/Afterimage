package gg.sona.afterimage.world

import java.util.*

interface GameNames {
    val protocolVersion: Int

    fun itemName(id: Int): String?

    fun itemId(name: String): Int?

    fun entityName(kind: EntityKind, type: Int): String?

    fun effectName(id: Int): String?

    fun isProjectile(kind: EntityKind, type: Int): Boolean

    fun itemLabel(id: Int): String = itemName(id)?.replace('_', ' ') ?: "item $id"

    fun effectLabel(id: Int): String = effectName(id) ?: "effect $id"

    fun entityLabel(kind: EntityKind, type: Int, id: Int): String = entityName(kind, type) ?: when (kind) {
        EntityKind.PLAYER -> "Player #$id"
        EntityKind.MOB -> "mob $type"
        EntityKind.OBJECT -> "object $type"
        EntityKind.PAINTING -> "painting"
        EntityKind.EXPERIENCE_ORB -> "xp orb"
        EntityKind.GLOBAL -> "lightning"
    }

    fun itemMatches(id: Int, query: String): Boolean {
        if (id < 0) return false
        val wanted = query.trim().lowercase().replace(' ', '_')
        if (wanted.isEmpty()) return false
        wanted.toIntOrNull()?.let { return it == id }
        val name = itemName(id) ?: return false
        return name == wanted || name.contains(wanted)
    }

    object Unknown : GameNames {
        override val protocolVersion: Int get() = -1
        override fun itemName(id: Int): String? = null
        override fun itemId(name: String): Int? = null
        override fun entityName(kind: EntityKind, type: Int): String? = null
        override fun effectName(id: Int): String? = null
        override fun isProjectile(kind: EntityKind, type: Int): Boolean = false
    }

    companion object {
        private val registered = LinkedHashMap<Int, GameNames>()

        @Volatile
        private var discovered = false

        fun register(names: GameNames) {
            synchronized(registered) { registered[names.protocolVersion] = names }
        }

        fun forProtocol(version: Int): GameNames {
            if (!discovered) synchronized(registered) {
                if (!discovered) {
                    discovered = true
                    for (names in ServiceLoader.load(GameNames::class.java, GameNames::class.java.classLoader)) {
                        registered.putIfAbsent(names.protocolVersion, names)
                    }
                }
            }
            synchronized(registered) { return registered[version] ?: Unknown }
        }
    }
}
