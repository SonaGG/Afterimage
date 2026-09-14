package gg.sona.recast.mc

import gg.sona.recast.net.PacketWriter
import gg.sona.recast.protocol.SlotCodec
import io.netty.buffer.Unpooled
import net.minecraft.item.ItemStack
import net.minecraft.network.PacketByteBuf

object ItemStacks {
    private val writer = PacketWriter(256)

    fun toMinecraft(item: gg.sona.recast.protocol.ItemStack): ItemStack? {
        if (item.isEmpty) return null
        writer.reset()
        SlotCodec.write(writer, item)
        val buffer = PacketByteBuf(Unpooled.wrappedBuffer(writer.toByteArray()))
        return try {
            buffer.readItem()
        } catch (error: Exception) {
            null
        } finally {
            buffer.release()
        }
    }
}
