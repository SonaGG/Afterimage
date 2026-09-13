package gg.sona.recast.replay.state.shadow

import gg.sona.recast.protocol.ItemStack
import gg.sona.recast.replay.state.diff.StateDiff

class ShadowWindow(val id: Int, val type: String, val titleJson: String, val size: Int, val horseEntityId: Int) {
    val items = ArrayList<ItemStack>()
    val properties = LinkedHashMap<Int, Int>()
    var trades: ByteArray? = null
        private set

    var version: Int = 0
        private set

    fun setItems(list: List<ItemStack>) {
        items.clear()
        items.addAll(list)
        version++
    }

    fun setItem(slot: Int, item: ItemStack) {
        if (slot < 0) return
        while (items.size <= slot) items += ItemStack.EMPTY
        items[slot] = item
        version++
    }

    fun setProperty(property: Int, value: Int) {
        properties[property] = value
        version++
    }

    fun setTrades(data: ByteArray) {
        trades = data
        version++
    }

    fun copy(): ShadowWindow {
        val window = ShadowWindow(id, type, titleJson, size, horseEntityId)
        window.items.addAll(items)
        window.properties.putAll(properties)
        window.trades = trades
        window.version = version
        return window
    }

    fun sameContents(other: ShadowWindow): Boolean {
        if (items.size != other.items.size || properties != other.properties) return false
        if (!trades.contentEquals(other.trades)) return false
        for (index in items.indices) if (!StateDiff.sameItem(items[index], other.items[index])) return false
        return true
    }

    fun sameIdentity(other: ShadowWindow): Boolean =
        id == other.id && type == other.type && titleJson == other.titleJson && size == other.size && horseEntityId == other.horseEntityId
}
