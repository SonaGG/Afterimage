package gg.sona.recast.format.compressor

import com.github.luben.zstd.Zstd
import gg.sona.recast.format.RecastFormatException
import gg.sona.recast.format.SegmentCompressor

class ZstdCompressor(private val level: Int = 3) : SegmentCompressor {
    override val id: Int get() = SegmentCompressor.ZSTD

    override fun compress(source: ByteArray, length: Int): ByteArray {
        val bound = Zstd.compressBound(length.toLong()).toInt()
        val output = ByteArray(bound)
        val produced = Zstd.compressByteArray(output, 0, bound, source, 0, length, level)
        if (Zstd.isError(produced)) throw RecastFormatException("zstd compression failed: ${Zstd.getErrorName(produced)}")
        return output.copyOf(produced.toInt())
    }

    override fun decompress(source: ByteArray, rawLength: Int): ByteArray {
        val output = ByteArray(rawLength)
        val produced = Zstd.decompressByteArray(output, 0, rawLength, source, 0, source.size)
        if (Zstd.isError(produced)) throw RecastFormatException("zstd decompression failed: ${Zstd.getErrorName(produced)}")
        if (produced.toInt() != rawLength) throw RecastFormatException("zstd segment produced $produced of $rawLength bytes")
        return output
    }
}
