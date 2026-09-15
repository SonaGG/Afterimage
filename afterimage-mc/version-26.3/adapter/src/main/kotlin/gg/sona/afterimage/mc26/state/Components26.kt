package gg.sona.afterimage.mc26.state

import com.google.gson.JsonParser
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.JsonOps
import net.minecraft.core.HolderLookup
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import com.google.gson.JsonElement

object Components26 {
    fun json(component: Component, registries: HolderLookup.Provider? = null): String {
        val ops = ops(registries)
        val encoded = ComponentSerialization.CODEC.encodeStart(ops, component).result().orElse(null)
        return encoded?.toString() ?: PacketEvents26.literal(component.string)
    }

    fun parse(json: String, registries: HolderLookup.Provider? = null): Component? {
        val element = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        val decoded = ComponentSerialization.CODEC.parse(ops(registries), element).result().orElse(null)
        return decoded ?: runCatching { Component.literal(element.asJsonObject.get("text").asString) }.getOrNull()
    }

    private fun ops(registries: HolderLookup.Provider?): DynamicOps<JsonElement> =
        registries?.createSerializationContext(JsonOps.INSTANCE) ?: JsonOps.INSTANCE
}
