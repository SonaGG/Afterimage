package gg.sona.afterimage.net

enum class PacketDirection(val bit: Int) {
    CLIENTBOUND(0),
    SERVERBOUND(1);

    companion object {
        fun ofBit(bit: Int): PacketDirection = if (bit == 0) CLIENTBOUND else SERVERBOUND
    }
}
