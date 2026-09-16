package gg.sona.afterimage.mc189.state

import gg.sona.afterimage.protocol.MapIcon


class MapCanvas(val mapId: Int) {
    var scale: Int = 0
    var icons = emptyList<MapIcon>()
    val pixels = ByteArray(128 * 128)
    var hasPixels = false
}