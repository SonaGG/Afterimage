package gg.sona.afterimage.mc

import gg.sona.afterimage.camera.CameraPose
import net.minecraft.entity.Entity
import net.minecraft.nbt.NbtCompound
import net.minecraft.world.World

class ReplayCameraEntity(world: World) : Entity(world) {

    init {
        noClip = true
        setSize(0f, 0f)
    }

    override fun registerSyncedData() = Unit

    override fun readCustomNbt(nbt: NbtCompound) = Unit

    override fun writeCustomNbt(nbt: NbtCompound) = Unit

    override fun getEyeHeight(): Float = 0f

    fun place(pose: CameraPose) {
        setPositionAndAngles(
            pose.position.x,
            pose.position.y,
            pose.position.z,
            pose.rotation.yaw.toFloat(),
            pose.rotation.pitch.toFloat()
        )
        prevX = x
        prevY = y
        prevZ = z
        lastYaw = yaw
        lastPitch = pitch
    }
}
