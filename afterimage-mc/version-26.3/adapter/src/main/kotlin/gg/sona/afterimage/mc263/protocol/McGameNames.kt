package gg.sona.afterimage.mc263.protocol

import gg.sona.afterimage.mc263.net.PacketIds
import gg.sona.afterimage.world.EntityKind
import gg.sona.afterimage.world.GameNames
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.projectile.Projectile

class McGameNames : GameNames {
    override val protocolVersion: Int get() = PacketIds.protocolVersion

    override fun itemName(id: Int): String? {
        if (id < 0) return null
        val item = BuiltInRegistries.ITEM.byId(id) ?: return null
        return BuiltInRegistries.ITEM.getKey(item).path
    }

    override fun itemId(name: String): Int? {
        val key = Identifier.tryParse(name.trim().lowercase().replace(' ', '_')) ?: return null
        val item = BuiltInRegistries.ITEM.getOptional(key).orElse(null) ?: return null
        return BuiltInRegistries.ITEM.getId(item)
    }

    override fun entityName(kind: EntityKind, type: Int): String? {
        if (kind == EntityKind.PLAYER) return null
        val entityType = BuiltInRegistries.ENTITY_TYPE.byId(type) ?: return null
        return BuiltInRegistries.ENTITY_TYPE.getKey(entityType).path.replace('_', ' ')
    }

    override fun effectName(id: Int): String? {
        val effect = BuiltInRegistries.MOB_EFFECT.byId(id) ?: return null
        return BuiltInRegistries.MOB_EFFECT.getKey(effect)?.path?.replace('_', ' ')
    }

    override fun isProjectile(kind: EntityKind, type: Int): Boolean {
        if (kind == EntityKind.PLAYER) return false
        val entityType = BuiltInRegistries.ENTITY_TYPE.byId(type) ?: return false
        return Projectile::class.java.isAssignableFrom(entityType.baseClass)
    }

    companion object {
        val INSTANCE = McGameNames()
    }
}
