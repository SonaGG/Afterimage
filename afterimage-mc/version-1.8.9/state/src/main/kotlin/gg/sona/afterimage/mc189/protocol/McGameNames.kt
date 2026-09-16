package gg.sona.afterimage.mc189.protocol

import gg.sona.afterimage.protocol.Protocol
import gg.sona.afterimage.world.EntityKind
import gg.sona.afterimage.world.GameNames

class McGameNames : GameNames {
    override val protocolVersion: Int get() = Protocol.VERSION

    override fun itemName(id: Int): String? = ItemNames.name(id)

    override fun itemId(name: String): Int? = ItemNames.id(name)

    override fun entityName(kind: EntityKind, type: Int): String? = when (kind) {
        EntityKind.MOB -> EntityNames.MOBS[type]
        EntityKind.OBJECT -> EntityNames.OBJECTS[type]
        else -> null
    }

    override fun effectName(id: Int): String? = ItemNames.EFFECTS[id]

    override fun isProjectile(kind: EntityKind, type: Int): Boolean = kind == EntityKind.OBJECT && type in EntityNames.PROJECTILES

    override fun itemMatches(id: Int, query: String): Boolean {
        if (id < 0) return false
        val needle = query.trim().lowercase().replace(' ', '_')
        if (needle.isEmpty()) return false
        needle.toIntOrNull()?.let { return it == id }
        val name = ItemNames.name(id) ?: return false
        if (name == needle) return true
        return name.split('_').any { it == needle } || name.endsWith("_$needle") || name.startsWith("${needle}_")
    }

    companion object {
        val INSTANCE = McGameNames()
    }
}
