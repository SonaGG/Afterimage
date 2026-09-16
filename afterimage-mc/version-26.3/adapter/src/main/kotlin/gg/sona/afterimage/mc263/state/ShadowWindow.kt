package gg.sona.afterimage.mc263.state

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket
import net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.world.item.ItemStack

class ShadowWindow(val id: Int, val open: ClientboundOpenScreenPacket?, val mount: ClientboundMountScreenOpenPacket?, val openedAtNanos: Long) {
    var items: MutableList<ItemStack> = ArrayList()
    var stateId: Int = 0
    var carried: ItemStack = ItemStack.EMPTY
    val properties = LinkedHashMap<Int, Int>()
    var offers: ClientboundMerchantOffersPacket? = null
    var version: Int = 0
        private set

    val size: Int get() = items.size
    val type: String get() = open?.type?.let { net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(it)?.toString() } ?: if (mount != null) "mount" else ""
    val title: net.minecraft.network.chat.Component? get() = open?.title

    fun apply(packet: ClientboundContainerSetContentPacket) {
        if (packet.containerId() != id) return
        items = ArrayList(packet.items())
        stateId = packet.stateId()
        carried = packet.carriedItem()
        version++
    }

    fun apply(packet: ClientboundContainerSetSlotPacket) {
        if (packet.containerId != id) return
        while (items.size <= packet.slot) items.add(ItemStack.EMPTY)
        items[packet.slot] = packet.item
        stateId = packet.stateId
        version++
    }

    fun updateCarried(item: ItemStack) {
        if (ItemStack.matches(carried, item)) return
        carried = item
        version++
    }

    fun apply(packet: ClientboundContainerSetDataPacket) {
        if (packet.containerId != id) return
        properties[packet.id] = packet.value
        version++
    }

    fun apply(packet: ClientboundMerchantOffersPacket) {
        if (packet.containerId != id) return
        offers = packet
        version++
    }

    fun snapshot(out: MutableList<Packet<*>>) {
        open?.let { out += it }
        mount?.let { out += it }
        if (items.isNotEmpty()) out += ClientboundContainerSetContentPacket(id, stateId, ArrayList(items), carried)
        for ((property, value) in properties) out += ClientboundContainerSetDataPacket(id, property, value)
        offers?.let { out += it }
    }

    fun sameLayout(other: ShadowWindow?): Boolean = other != null && other.id == id && other.type == type && other.openedAtNanos == openedAtNanos
}
