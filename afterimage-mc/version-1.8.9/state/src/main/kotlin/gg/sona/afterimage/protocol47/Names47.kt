package gg.sona.afterimage.protocol47

import gg.sona.afterimage.protocol.Protocol
import gg.sona.afterimage.world.EntityKind
import gg.sona.afterimage.world.GameNames

class Names47 : GameNames {
    override val protocolVersion: Int get() = Protocol.VERSION

    override fun itemName(id: Int): String? = Items47.name(id)

    override fun itemId(name: String): Int? = Items47.id(name)

    override fun entityName(kind: EntityKind, type: Int): String? = when (kind) {
        EntityKind.MOB -> EntityNames47.MOBS[type]
        EntityKind.OBJECT -> EntityNames47.OBJECTS[type]
        else -> null
    }

    override fun effectName(id: Int): String? = Items47.EFFECTS[id]

    override fun isProjectile(kind: EntityKind, type: Int): Boolean = kind == EntityKind.OBJECT && type in EntityNames47.PROJECTILES

    override fun itemMatches(id: Int, query: String): Boolean {
        if (id < 0) return false
        val needle = query.trim().lowercase().replace(' ', '_')
        if (needle.isEmpty()) return false
        needle.toIntOrNull()?.let { return it == id }
        val name = Items47.name(id) ?: return false
        if (name == needle) return true
        return name.split('_').any { it == needle } || name.endsWith("_$needle") || name.startsWith("${needle}_")
    }

    companion object {
        val INSTANCE = Names47()
    }
}
