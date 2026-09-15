package gg.sona.afterimage.protocol47.shadow

import gg.sona.afterimage.world.ObjectiveState

class Objective(override val name: String, override var displayName: String, var type: String) : ObjectiveState {
    override val scores = LinkedHashMap<String, Int>()
}
