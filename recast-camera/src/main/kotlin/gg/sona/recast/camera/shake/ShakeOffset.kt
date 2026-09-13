package gg.sona.recast.camera.shake

import gg.sona.recast.camera.Rotation
import org.joml.Vector3d

data class ShakeOffset(val position: Vector3d, val rotation: Rotation) {
    companion object {
        val NONE = ShakeOffset(Vector3d(), Rotation.ZERO)
    }
}