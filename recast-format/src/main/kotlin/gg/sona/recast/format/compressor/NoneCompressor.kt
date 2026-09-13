package gg.sona.recast.format.compressor

import gg.sona.recast.format.SegmentCompressor

object NoneCompressor : SegmentCompressor {
    override val id: Int get() = SegmentCompressor.NONE

    override fun compress(source: ByteArray, length: Int): ByteArray = source.copyOf(length)

    override fun decompress(source: ByteArray, rawLength: Int): ByteArray = source.copyOf(rawLength)
}
