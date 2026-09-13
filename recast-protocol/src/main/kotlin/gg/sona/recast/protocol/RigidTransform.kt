package gg.sona.recast.protocol

import gg.sona.recast.net.PacketReader
import gg.sona.recast.net.PacketWriter
import kotlin.math.abs
import kotlin.math.sqrt

object RigidTransform {

    private const val TOLERANCE = 1e-4f

    fun isRigid(m: FloatArray): Boolean {
        if (m.size != 16) return false
        if (abs(m[3]) > TOLERANCE || abs(m[7]) > TOLERANCE || abs(m[11]) > TOLERANCE || abs(m[15] - 1f) > TOLERANCE) return false
        for (column in 0 until 3) {
            val x = m[column * 4]
            val y = m[column * 4 + 1]
            val z = m[column * 4 + 2]
            if (abs(x * x + y * y + z * z - 1f) > TOLERANCE * 10f) return false
        }
        for (a in 0 until 3) for (b in a + 1 until 3) {
            val dot = m[a * 4] * m[b * 4] + m[a * 4 + 1] * m[b * 4 + 1] + m[a * 4 + 2] * m[b * 4 + 2]
            if (abs(dot) > TOLERANCE * 10f) return false
        }
        val det =
            m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])
        return abs(det - 1f) < TOLERANCE * 10f
    }

    fun write(writer: PacketWriter, m: FloatArray) {
        val r00 = m[0]
        val r10 = m[1]
        val r20 = m[2]
        val r01 = m[4]
        val r11 = m[5]
        val r21 = m[6]
        val r02 = m[8]
        val r12 = m[9]
        val r22 = m[10]
        val trace = r00 + r11 + r22
        var qw: Float
        var qx: Float
        var qy: Float
        var qz: Float
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            qw = 0.25f * s
            qx = (r21 - r12) / s
            qy = (r02 - r20) / s
            qz = (r10 - r01) / s
        } else if (r00 > r11 && r00 > r22) {
            val s = sqrt(1f + r00 - r11 - r22) * 2f
            qw = (r21 - r12) / s
            qx = 0.25f * s
            qy = (r01 + r10) / s
            qz = (r02 + r20) / s
        } else if (r11 > r22) {
            val s = sqrt(1f + r11 - r00 - r22) * 2f
            qw = (r02 - r20) / s
            qx = (r01 + r10) / s
            qy = 0.25f * s
            qz = (r12 + r21) / s
        } else {
            val s = sqrt(1f + r22 - r00 - r11) * 2f
            qw = (r10 - r01) / s
            qx = (r02 + r20) / s
            qy = (r12 + r21) / s
            qz = 0.25f * s
        }
        val length = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        if (length > 0f) {
            qw /= length
            qx /= length
            qy /= length
            qz /= length
        }
        writer.writeFloat(qw).writeFloat(qx).writeFloat(qy).writeFloat(qz)
        writer.writeFloat(m[12]).writeFloat(m[13]).writeFloat(m[14])
    }

    fun read(reader: PacketReader): FloatArray {
        val qw = reader.readFloat()
        val qx = reader.readFloat()
        val qy = reader.readFloat()
        val qz = reader.readFloat()
        val tx = reader.readFloat()
        val ty = reader.readFloat()
        val tz = reader.readFloat()
        val m = FloatArray(16)
        m[0] = 1f - 2f * (qy * qy + qz * qz)
        m[1] = 2f * (qx * qy + qz * qw)
        m[2] = 2f * (qx * qz - qy * qw)
        m[4] = 2f * (qx * qy - qz * qw)
        m[5] = 1f - 2f * (qx * qx + qz * qz)
        m[6] = 2f * (qy * qz + qx * qw)
        m[8] = 2f * (qx * qz + qy * qw)
        m[9] = 2f * (qy * qz - qx * qw)
        m[10] = 1f - 2f * (qx * qx + qy * qy)
        m[12] = tx
        m[13] = ty
        m[14] = tz
        m[15] = 1f
        return m
    }
}
