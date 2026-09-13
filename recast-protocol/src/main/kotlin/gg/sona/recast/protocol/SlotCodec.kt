package gg.sona.recast.protocol

import gg.sona.recast.net.PacketReader
import gg.sona.recast.net.PacketWriter

object SlotCodec {

    fun read(reader: PacketReader): ItemStack {
        val id = reader.readShort().toInt()
        if (id < 0) return ItemStack.EMPTY
        val count = reader.readByte().toInt()
        val damage = reader.readShort().toInt()
        val tag = reader.readNbt()
        return ItemStack(id, count, damage, tag)
    }

    fun write(writer: PacketWriter, stack: ItemStack?) {
        if (stack == null || stack.isEmpty) {
            writer.writeShort(-1)
            return
        }
        writer.writeShort(stack.id)
        writer.writeByte(stack.count)
        writer.writeShort(stack.damage)
        writer.writeNbt(stack.tag)
    }
}
