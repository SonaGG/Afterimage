package gg.sona.afterimage.format.compressor

import gg.sona.afterimage.format.SegmentCompressor

object NoneCompressor : SegmentCompressor {
    override val id: Int get() = SegmentCompressor.NONE

    override fun compress(source: ByteArray, length: Int): ByteArray = source.copyOf(length)

    override fun decompress(source: ByteArray, rawLength: Int): ByteArray = source.copyOf(rawLength)
}
