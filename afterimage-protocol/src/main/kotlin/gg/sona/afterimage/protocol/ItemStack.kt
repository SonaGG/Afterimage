package gg.sona.afterimage.protocol

import gg.sona.afterimage.net.nbt.NbtCompound

class ItemStack(val id: Int, val count: Int, val damage: Int, val tag: NbtCompound?) {

    val isEmpty: Boolean get() = id < 0

    fun displayName(): String? = tag?.compound("display")?.string("Name")

    override fun toString(): String =
        if (isEmpty) "ItemStack(empty)" else "ItemStack($id x$count:$damage${if (tag != null) " +nbt" else ""})"

    companion object {
        val EMPTY = ItemStack(-1, 0, 0, null)
    }
}
