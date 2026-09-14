package gg.sona.afterimage.net.nbt

import gg.sona.afterimage.net.PacketFormatException
import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.net.PacketWriter

object NbtCodec {

    private const val MAX_DEPTH = 512

    fun readNetwork(reader: PacketReader): NbtCompound? {
        val type = reader.readUnsignedByte()
        if (type == 0) return null
        if (type != 10) throw PacketFormatException("network nbt root must be a compound, got $type")
        reader.readUtf()
        return readCompound(reader, 0)
    }

    fun writeNetwork(writer: PacketWriter, compound: NbtCompound?) {
        if (compound == null) {
            writer.writeByte(0)
            return
        }
        writer.writeByte(10)
        writer.writeUtf("")
        writeCompound(writer, compound)
    }

    fun readPayload(reader: PacketReader, type: Int, depth: Int): NbtTag = when (type) {
        0 -> NbtEnd
        1 -> NbtByte(reader.readByte())
        2 -> NbtShort(reader.readShort())
        3 -> NbtInt(reader.readInt())
        4 -> NbtLong(reader.readLong())
        5 -> NbtFloat(reader.readFloat())
        6 -> NbtDouble(reader.readDouble())
        7 -> NbtByteArray(reader.readBytes(checkedLength(reader, reader.readInt())))
        8 -> NbtString(reader.readUtf())
        9 -> readList(reader, depth)
        10 -> readCompound(reader, depth)
        11 -> {
            val length = checkedLength(reader, reader.readInt() * 4) / 4
            NbtIntArray(IntArray(length) { reader.readInt() })
        }

        else -> throw PacketFormatException("unknown nbt tag type $type")
    }

    fun writePayload(writer: PacketWriter, tag: NbtTag) {
        when (tag) {
            NbtEnd -> Unit
            is NbtByte -> writer.writeByte(tag.value.toInt())
            is NbtShort -> writer.writeShort(tag.value.toInt())
            is NbtInt -> writer.writeInt(tag.value)
            is NbtLong -> writer.writeLong(tag.value)
            is NbtFloat -> writer.writeFloat(tag.value)
            is NbtDouble -> writer.writeDouble(tag.value)
            is NbtByteArray -> {
                writer.writeInt(tag.value.size)
                writer.writeBytes(tag.value)
            }

            is NbtString -> writer.writeUtf(tag.value)
            is NbtList -> {
                writer.writeByte(tag.elementType)
                writer.writeInt(tag.elements.size)
                for (element in tag.elements) writePayload(writer, element)
            }

            is NbtCompound -> writeCompound(writer, tag)
            is NbtIntArray -> {
                writer.writeInt(tag.value.size)
                for (value in tag.value) writer.writeInt(value)
            }
        }
    }

    private fun readList(reader: PacketReader, depth: Int): NbtList {
        if (depth > MAX_DEPTH) throw PacketFormatException("nbt nesting too deep")
        val elementType = reader.readUnsignedByte()
        val count = reader.readInt()
        if (count < 0) throw PacketFormatException("negative nbt list length")
        if (elementType == 0 && count > 0) throw PacketFormatException("nbt list of end tags")
        val elements = ArrayList<NbtTag>(count.coerceAtMost(1024))
        repeat(count) { elements += readPayload(reader, elementType, depth + 1) }
        return NbtList(elementType, elements)
    }

    private fun readCompound(reader: PacketReader, depth: Int): NbtCompound {
        if (depth > MAX_DEPTH) throw PacketFormatException("nbt nesting too deep")
        val compound = NbtCompound()
        while (true) {
            val type = reader.readUnsignedByte()
            if (type == 0) return compound
            val name = reader.readUtf()
            compound.entries[name] = readPayload(reader, type, depth + 1)
        }
    }

    private fun writeCompound(writer: PacketWriter, compound: NbtCompound) {
        for ((name, tag) in compound.entries) {
            if (tag === NbtEnd) continue
            writer.writeByte(tag.typeId)
            writer.writeUtf(name)
            writePayload(writer, tag)
        }
        writer.writeByte(0)
    }

    private fun checkedLength(reader: PacketReader, length: Int): Int {
        if (length < 0 || length > reader.remaining) throw PacketFormatException("nbt array length $length out of range")
        return length
    }
}
