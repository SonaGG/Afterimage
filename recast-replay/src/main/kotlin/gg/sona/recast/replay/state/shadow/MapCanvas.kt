package gg.sona.recast.replay.state.shadow

import gg.sona.recast.protocol.MapIcon


class MapCanvas(val mapId: Int) {
    var scale: Int = 0
    var icons = emptyList<MapIcon>()
    val pixels = ByteArray(128 * 128)
    var hasPixels = false
}