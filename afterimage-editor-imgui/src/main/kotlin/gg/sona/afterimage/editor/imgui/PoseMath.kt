package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.editor.pose.BodyPart
import org.joml.Matrix3d
import org.joml.Vector3d

class PoseMath(model: DoubleArray) {
    val position = Vector3d(model[0], model[1] - if (model[4] > 0.5) SNEAK_OFFSET else 0.0, model[2])
    val bodyYaw = model[3]
    private val data = model

    val linear: Matrix3d = Matrix3d().rotationY(Math.toRadians(180.0 - bodyYaw)).scale(-1.0, -1.0, 1.0)
    private val inverse: Matrix3d = Matrix3d(linear).invert()

    fun pivot(part: BodyPart): Vector3d {
        val base = HEADER + part.ordinal * STRIDE
        return toWorld(Vector3d(data[base], data[base + 1], data[base + 2]))
    }

    fun rotation(part: BodyPart): Vector3d {
        val base = HEADER + part.ordinal * STRIDE
        return Vector3d(data[base + 3], data[base + 4], data[base + 5])
    }

    fun rotationMatrix(rotationRadians: Vector3d): Matrix3d =
        Matrix3d().rotateZ(rotationRadians.z).rotateY(rotationRadians.y).rotateX(rotationRadians.x)

    fun corners(part: BodyPart, rotationRadians: Vector3d): List<Vector3d> {
        val base = HEADER + part.ordinal * STRIDE
        val pivot = Vector3d(data[base], data[base + 1], data[base + 2])
        val rotation = rotationMatrix(rotationRadians)
        val result = ArrayList<Vector3d>(8)
        for (index in 0 until 8) {
            val local = Vector3d(
                if (index and 1 == 0) part.minX else part.maxX,
                if (index and 2 == 0) part.minY else part.maxY,
                if (index and 4 == 0) part.minZ else part.maxZ,
            )
            result += toWorld(rotation.transform(local).add(pivot))
        }
        return result
    }

    fun ringAxes(): Array<Vector3d> = arrayOf(
        linear.transform(Vector3d(-1.0, 0.0, 0.0)).normalize(),
        Vector3d(0.0, 1.0, 0.0),
        linear.transform(Vector3d(0.0, 0.0, -1.0)).normalize(),
    )

    fun rotated(rotationRadians: Vector3d, axis: Vector3d, degrees: Double): Vector3d {
        val world = Matrix3d().rotate(Math.toRadians(degrees), axis.x, axis.y, axis.z)
        val result = Matrix3d(inverse).mul(world).mul(linear).mul(rotationMatrix(rotationRadians))
        return result.getEulerAnglesZYX(Vector3d())
    }

    private fun toWorld(model: Vector3d): Vector3d {
        val scaled = Vector3d(model).mul(1.0 / 16.0).add(0.0, MODEL_OFFSET, 0.0)
        return linear.transform(scaled).add(position)
    }

    companion object {
        const val HEADER = 5
        const val STRIDE = 6
        const val MODEL_OFFSET = -1.5078125
        const val SNEAK_OFFSET = 0.125

        fun degrees(radians: Vector3d): Vector3d =
            Vector3d(Math.toDegrees(radians.x), Math.toDegrees(radians.y), Math.toDegrees(radians.z))

        fun radians(degrees: Vector3d): Vector3d =
            Vector3d(Math.toRadians(degrees.x), Math.toRadians(degrees.y), Math.toRadians(degrees.z))
    }
}
