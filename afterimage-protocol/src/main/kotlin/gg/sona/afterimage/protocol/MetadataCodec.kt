package gg.sona.afterimage.protocol

import gg.sona.afterimage.net.PacketFormatException
import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.net.PacketWriter

object MetadataCodec {

    fun read(reader: PacketReader): List<MetadataEntry> {
        val entries = ArrayList<MetadataEntry>(8)
        while (true) {
            val item = reader.readUnsignedByte()
            if (item == MetadataEntry.TERMINATOR) return entries
            val type = (item and 0xE0) shr 5
            val index = item and 0x1F
            val value: Any = when (type) {
                MetadataEntry.BYTE -> reader.readByte()
                MetadataEntry.SHORT -> reader.readShort()
                MetadataEntry.INT -> reader.readInt()
                MetadataEntry.FLOAT -> reader.readFloat()
                MetadataEntry.STRING -> reader.readString()
                MetadataEntry.SLOT -> SlotCodec.read(reader)
                MetadataEntry.BLOCK_POS -> MetaBlockPos(reader.readInt(), reader.readInt(), reader.readInt())
                MetadataEntry.ROTATIONS -> MetaRotations(reader.readFloat(), reader.readFloat(), reader.readFloat())
                else -> throw PacketFormatException("unknown metadata type $type")
            }
            entries += MetadataEntry(index, type, value)
        }
    }

    fun write(writer: PacketWriter, entries: Iterable<MetadataEntry>) {
        for (entry in entries) {
            writer.writeByte(((entry.type shl 5) or (entry.index and 0x1F)) and 0xFF)
            when (entry.type) {
                MetadataEntry.BYTE -> writer.writeByte((entry.value as Byte).toInt())
                MetadataEntry.SHORT -> writer.writeShort((entry.value as Short).toInt())
                MetadataEntry.INT -> writer.writeInt(entry.value as Int)
                MetadataEntry.FLOAT -> writer.writeFloat(entry.value as Float)
                MetadataEntry.STRING -> writer.writeString(entry.value as String)
                MetadataEntry.SLOT -> SlotCodec.write(writer, entry.value as ItemStack)
                MetadataEntry.BLOCK_POS -> {
                    val position = entry.value as MetaBlockPos
                    writer.writeInt(position.x).writeInt(position.y).writeInt(position.z)
                }

                MetadataEntry.ROTATIONS -> {
                    val rotations = entry.value as MetaRotations
                    writer.writeFloat(rotations.x).writeFloat(rotations.y).writeFloat(rotations.z)
                }
            }
        }
        writer.writeByte(MetadataEntry.TERMINATOR)
    }
}
