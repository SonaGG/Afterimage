package gg.sona.afterimage.protocol47.shadow

import gg.sona.afterimage.protocol.MapIcon


class MapCanvas(val mapId: Int) {
    var scale: Int = 0
    var icons = emptyList<MapIcon>()
    val pixels = ByteArray(128 * 128)
    var hasPixels = false
}