package gg.sona.recast.render

import gg.sona.recast.replay.session.ReplaySession

interface ReplayDriver {
    fun pause()

    fun advanceTo(nanos: Long)
}

fun ReplaySession.exportDriver(): ReplayDriver = object : ReplayDriver {
    override fun pause() = this@exportDriver.pause()

    override fun advanceTo(nanos: Long) = this@exportDriver.advanceTo(nanos)
}
