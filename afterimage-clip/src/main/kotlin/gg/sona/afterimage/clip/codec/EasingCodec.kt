package gg.sona.afterimage.clip.codec

import gg.sona.afterimage.camera.Easing
import gg.sona.afterimage.camera.EasingKind
import gg.sona.afterimage.camera.track.Extrapolation
import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.net.PacketWriter

object EasingCodec {
    fun write(writer: PacketWriter, easing: Easing) {
        writer.writeByte(easing.kind.ordinal)
        if (easing.kind == EasingKind.CUSTOM) {
            writer.writeFloat(easing.x1.toFloat()).writeFloat(easing.y1.toFloat())
            writer.writeFloat(easing.x2.toFloat()).writeFloat(easing.y2.toFloat())
        }
    }

    fun read(reader: PacketReader, legacy: Boolean = false): Easing {
        val ordinal = reader.readUnsignedByte()
        if (legacy) return Easing.legacy(ordinal)
        val kind = EasingKind.entries[ordinal.coerceIn(0, EasingKind.entries.size - 1)]
        if (kind != EasingKind.CUSTOM) return Easing(kind)
        val x1 = reader.readFloat().toDouble()
        val y1 = reader.readFloat().toDouble()
        val x2 = reader.readFloat().toDouble()
        val y2 = reader.readFloat().toDouble()
        return Easing(EasingKind.CUSTOM, x1, y1, x2, y2)
    }

    fun writeExtrapolation(writer: PacketWriter, pre: Extrapolation, post: Extrapolation) {
        writer.writeByte(pre.ordinal).writeByte(post.ordinal)
    }

    fun readExtrapolation(reader: PacketReader): Pair<Extrapolation, Extrapolation> {
        val entries = Extrapolation.entries
        val pre = entries[reader.readUnsignedByte().coerceIn(0, entries.size - 1)]
        val post = entries[reader.readUnsignedByte().coerceIn(0, entries.size - 1)]
        return pre to post
    }
}
