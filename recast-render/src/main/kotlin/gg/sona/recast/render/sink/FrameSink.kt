package gg.sona.recast.render.sink

import gg.sona.recast.render.ExportSettings
import gg.sona.recast.render.RenderedFrame

interface FrameSink : AutoCloseable {
    fun begin(settings: ExportSettings)

    fun accept(frame: RenderedFrame)

    fun abort() = close()
}
