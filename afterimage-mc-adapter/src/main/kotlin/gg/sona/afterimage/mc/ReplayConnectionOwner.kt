package gg.sona.afterimage.mc

import net.minecraft.network.Connection

interface ReplayConnectionOwner {
    fun `afterimage$connection`(): Connection?
}
