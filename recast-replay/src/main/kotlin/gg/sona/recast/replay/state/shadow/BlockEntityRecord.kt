package gg.sona.recast.replay.state.shadow

import gg.sona.recast.net.nbt.NbtCompound

class BlockEntityRecord(val position: Long, var action: Int, var nbt: NbtCompound?)
