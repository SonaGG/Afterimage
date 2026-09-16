package gg.sona.afterimage.mc263.replay

import gg.sona.afterimage.camera.CameraPose
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Pose
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

class ReplayCameraEntity(level: ClientLevel) : Entity(EntityTypes.MARKER, level) {
    init {
        noPhysics = true
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) = Unit

    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean = false

    override fun readAdditionalSaveData(input: ValueInput) = Unit

    override fun addAdditionalSaveData(output: ValueOutput) = Unit

    override fun getDimensions(pose: Pose): EntityDimensions = DIMENSIONS

    fun place(pose: CameraPose) {
        absSnapTo(pose.position.x, pose.position.y, pose.position.z, pose.rotation.yaw.toFloat(), pose.rotation.pitch.toFloat())
        setOldPosAndRot()
    }

    private companion object {
        val DIMENSIONS: EntityDimensions = EntityDimensions.fixed(0f, 0f)
    }
}
