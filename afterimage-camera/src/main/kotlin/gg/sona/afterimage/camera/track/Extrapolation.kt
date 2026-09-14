package gg.sona.afterimage.camera.track

enum class Extrapolation(val label: String, val description: String) {
    HOLD("Hold", "Stays on the first or last keyframe"),
    LINEAR("Linear", "Keeps moving along the curve's end tangent"),
    LOOP("Loop", "Repeats the whole track from the start"),
    PING_PONG("Ping pong", "Plays the track forwards then backwards"),
}
