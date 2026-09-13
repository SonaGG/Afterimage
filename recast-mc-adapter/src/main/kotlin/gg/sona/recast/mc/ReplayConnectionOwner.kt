package gg.sona.recast.mc

import net.minecraft.network.Connection

interface ReplayConnectionOwner {
    fun `recast$connection`(): Connection?
}
