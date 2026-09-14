package gg.sona.afterimage.format.compressor

import gg.sona.afterimage.format.AfterimageFormatException
import gg.sona.afterimage.format.SegmentCompressor
import java.util.zip.Deflater
import java.util.zip.Inflater

class DeflateCompressor(private val level: Int = Deflater.DEFAULT_COMPRESSION) : SegmentCompressor {
    override val id: Int get() = SegmentCompressor.DEFLATE

    override fun compress(source: ByteArray, length: Int): ByteArray {
        val deflater = Deflater(level)
        try {
            deflater.setInput(source, 0, length)
            deflater.finish()
            var output = ByteArray(maxOf(64, length / 2))
            var produced = 0
            while (!deflater.finished()) {
                if (produced == output.size) output = output.copyOf(output.size shl 1)
                produced += deflater.deflate(output, produced, output.size - produced)
            }
            return output.copyOf(produced)
        } finally {
            deflater.end()
        }
    }

    override fun decompress(source: ByteArray, rawLength: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(source)
            val output = ByteArray(rawLength)
            var produced = 0
            while (produced < rawLength) {
                val count = inflater.inflate(output, produced, rawLength - produced)
                if (count == 0 && (inflater.finished() || inflater.needsInput())) break
                produced += count
            }
            if (produced != rawLength) throw AfterimageFormatException("deflate segment produced $produced of $rawLength bytes")
            return output
        } finally {
            inflater.end()
        }
    }
}
