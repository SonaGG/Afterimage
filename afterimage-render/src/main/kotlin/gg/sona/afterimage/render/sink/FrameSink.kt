package gg.sona.afterimage.render.sink

import gg.sona.afterimage.render.ExportSettings
import gg.sona.afterimage.render.RenderedFrame

interface FrameSink : AutoCloseable {
    fun begin(settings: ExportSettings)

    fun accept(frame: RenderedFrame)

    fun abort() = close()
}
