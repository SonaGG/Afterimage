package gg.sona.afterimage.format

import gg.sona.afterimage.format.compressor.DeflateCompressor
import gg.sona.afterimage.format.compressor.NoneCompressor
import gg.sona.afterimage.format.compressor.ZstdCompressor

interface SegmentCompressor {
    val id: Int

    fun compress(source: ByteArray, length: Int): ByteArray

    fun decompress(source: ByteArray, rawLength: Int): ByteArray

    companion object {
        const val NONE = 0
        const val DEFLATE = 1
        const val ZSTD = 2

        fun ofId(id: Int): SegmentCompressor = when (id) {
            NONE -> NoneCompressor
            DEFLATE -> DeflateCompressor()
            ZSTD -> ZstdCompressor()
            else -> throw AfterimageFormatException("unknown segment codec $id")
        }
    }
}
