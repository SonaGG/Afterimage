package gg.sona.afterimage.mc

import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.protocol.ClientboundPlay
import io.netty.buffer.Unpooled
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screen.inventory.menu.CreativeInventoryScreen
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen
import net.minecraft.inventory.menu.InventoryMenu
import net.minecraft.item.ItemStack
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.packet.s2c.play.InventoryMenuSlotContentS2CPacket

class InventoryCapture(private val minecraft: Minecraft, private val offer: (PacketDirection, Int, ByteArray) -> Unit) {
    private var depth = 0
    private var menu: InventoryMenu? = null
    private var baseline: Array<ItemStack?> = emptyArray()
    private var cursor: ItemStack? = null

    fun before(screen: InventoryMenuScreen) {
        if (depth++ > 0) return
        val player = minecraft.player ?: return
        val tracked = if (screen is CreativeInventoryScreen) player.playerMenu else screen.menu
        menu = tracked
        baseline = snapshot(tracked)
        cursor = player.inventory.cursorItem?.copy()
    }

    fun after(screen: InventoryMenuScreen) {
        if (--depth > 0) return
        depth = 0
        val tracked = menu ?: return
        menu = null
        val player = minecraft.player ?: return
        val current = snapshot(tracked)
        for (index in current.indices) {
            val previous = baseline.getOrNull(index)
            if (!same(previous, current[index])) emit(tracked.networkId, index, current[index])
        }
        val nowCursor = player.inventory.cursorItem
        if (!same(cursor, nowCursor)) emit(-1, -1, nowCursor)
        baseline = emptyArray()
        cursor = null
    }

    private fun snapshot(menu: InventoryMenu): Array<ItemStack?> {
        val items = menu.getItems()
        return Array(items.size) { items[it]?.copy() }
    }

    private fun same(a: ItemStack?, b: ItemStack?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return ItemStack.matches(a, b)
    }

    private fun emit(windowId: Int, slot: Int, item: ItemStack?) {
        val buffer = PacketByteBuf(Unpooled.buffer(64))
        try {
            InventoryMenuSlotContentS2CPacket(windowId, slot, item?.copy()).write(buffer)
            val payload = ByteArray(buffer.readableBytes())
            buffer.readBytes(payload)
            offer(PacketDirection.CLIENTBOUND, ClientboundPlay.SET_SLOT, payload)
        } finally {
            buffer.release()
        }
    }
}
