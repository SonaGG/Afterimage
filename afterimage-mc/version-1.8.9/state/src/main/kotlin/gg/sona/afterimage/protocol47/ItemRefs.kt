package gg.sona.afterimage.protocol47

import gg.sona.afterimage.protocol.ItemStack
import gg.sona.afterimage.world.ItemRef

fun ItemStack?.toRef(): ItemRef? {
    if (this == null || isEmpty) return null
    return ItemRef(id, count, Names47.INSTANCE.itemLabel(id))
}
