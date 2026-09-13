package gg.sona.recast.replay.state.shadow

class Objective(val name: String, var displayName: String, var type: String) {
    val scores = LinkedHashMap<String, Int>()
}
