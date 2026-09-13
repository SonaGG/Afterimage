package gg.sona.recast.editor.host

class GizmoBatch {
    var linePositions = DoubleArray(6 * 256)
        private set
    var lineColors = IntArray(256)
        private set
    var lineWidths = FloatArray(256)
        private set
    var lineLayers = ByteArray(256)
        private set
    var lineCount = 0
        private set

    var triPositions = DoubleArray(9 * 256)
        private set
    var triColors = IntArray(256)
        private set
    var triLayers = ByteArray(256)
        private set
    var triCount = 0
        private set

    val isEmpty: Boolean get() = lineCount == 0 && triCount == 0

    fun clear() {
        lineCount = 0
        triCount = 0
    }

    fun line(
        x1: Double,
        y1: Double,
        z1: Double,
        x2: Double,
        y2: Double,
        z2: Double,
        color: Int,
        width: Float,
        layer: Int = WORLD
    ) {
        if (lineCount == lineColors.size) growLines()
        val base = lineCount * 6
        linePositions[base] = x1
        linePositions[base + 1] = y1
        linePositions[base + 2] = z1
        linePositions[base + 3] = x2
        linePositions[base + 4] = y2
        linePositions[base + 5] = z2
        lineColors[lineCount] = color
        lineWidths[lineCount] = width
        lineLayers[lineCount] = layer.toByte()
        lineCount++
    }

    fun triangle(
        x1: Double,
        y1: Double,
        z1: Double,
        x2: Double,
        y2: Double,
        z2: Double,
        x3: Double,
        y3: Double,
        z3: Double,
        color: Int,
        layer: Int = WORLD
    ) {
        if (triCount == triColors.size) growTriangles()
        val base = triCount * 9
        triPositions[base] = x1
        triPositions[base + 1] = y1
        triPositions[base + 2] = z1
        triPositions[base + 3] = x2
        triPositions[base + 4] = y2
        triPositions[base + 5] = z2
        triPositions[base + 6] = x3
        triPositions[base + 7] = y3
        triPositions[base + 8] = z3
        triColors[triCount] = color
        triLayers[triCount] = layer.toByte()
        triCount++
    }

    private fun growLines() {
        val size = lineColors.size * 2
        linePositions = linePositions.copyOf(size * 6)
        lineColors = lineColors.copyOf(size)
        lineWidths = lineWidths.copyOf(size)
        lineLayers = lineLayers.copyOf(size)
    }

    private fun growTriangles() {
        val size = triColors.size * 2
        triPositions = triPositions.copyOf(size * 9)
        triColors = triColors.copyOf(size)
        triLayers = triLayers.copyOf(size)
    }

    companion object {
        const val WORLD = 0
        const val OVERLAY = 1
    }
}
