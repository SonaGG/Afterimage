package gg.sona.recast.format

import gg.sona.recast.format.compressor.DeflateCompressor
import gg.sona.recast.format.compressor.NoneCompressor
import gg.sona.recast.format.compressor.ZstdCompressor

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
            else -> throw RecastFormatException("unknown segment codec $id")
        }
    }
}
