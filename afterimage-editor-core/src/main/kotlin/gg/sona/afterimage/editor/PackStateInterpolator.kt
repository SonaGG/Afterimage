package gg.sona.afterimage.editor

import gg.sona.afterimage.camera.track.interpolator.ValueInterpolator

object PackStateInterpolator : ValueInterpolator<PackState> {
    override fun lerp(a: PackState, b: PackState, t: Double): PackState = a

    override fun distance(a: PackState, b: PackState): Double = 0.0

    override fun bezier(p1: PackState, control1: PackState, control2: PackState, p2: PackState, t: Double): PackState = p1

    override fun tangentControl(previous: PackState, current: PackState, next: PackState, scale: Double): PackState = current
}
