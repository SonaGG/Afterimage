package gg.sona.afterimage.mc26

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack

class InventoryCapture26(private val minecraft: Minecraft, private val offer: (Packet<*>) -> Unit) {
    private var depth = 0
    private var menu: AbstractContainerMenu? = null
    private var baseline: Array<ItemStack> = emptyArray()
    private var cursor: ItemStack = ItemStack.EMPTY

    fun before(screen: AbstractContainerScreen<*>) {
        if (depth++ > 0) return
        val player = minecraft.player ?: return
        val tracked = if (screen is CreativeModeInventoryScreen) player.inventoryMenu else screen.menu
        menu = tracked
        baseline = snapshot(tracked)
        cursor = tracked.carried.copy()
    }

    fun after(screen: AbstractContainerScreen<*>) {
        if (--depth > 0) return
        depth = 0
        val tracked = menu ?: return
        menu = null
        val current = snapshot(tracked)
        for (index in current.indices) {
            val previous = baseline.getOrNull(index) ?: ItemStack.EMPTY
            if (!ItemStack.matches(previous, current[index])) offer(ClientboundContainerSetSlotPacket(tracked.containerId, tracked.stateId, index, current[index].copy()))
        }
        val nowCursor = tracked.carried
        if (!ItemStack.matches(cursor, nowCursor)) offer(ClientboundSetCursorItemPacket(nowCursor.copy()))
        baseline = emptyArray()
        cursor = ItemStack.EMPTY
    }

    private fun snapshot(menu: AbstractContainerMenu): Array<ItemStack> {
        val items = menu.items
        return Array(items.size) { items[it].copy() }
    }
}
