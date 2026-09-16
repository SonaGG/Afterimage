package gg.sona.afterimage.mc189.protocol

import gg.sona.afterimage.protocol.ItemStack
import gg.sona.afterimage.world.ItemRef

fun ItemStack?.toRef(): ItemRef? {
    if (this == null || isEmpty) return null
    return ItemRef(id, count, McGameNames.INSTANCE.itemLabel(id))
}
