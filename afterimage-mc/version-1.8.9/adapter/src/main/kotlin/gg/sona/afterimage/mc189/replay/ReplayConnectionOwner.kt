package gg.sona.afterimage.mc189.replay

import net.minecraft.network.Connection

interface ReplayConnectionOwner {
    fun `afterimage$connection`(): Connection?
}
