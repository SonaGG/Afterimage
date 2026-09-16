package gg.sona.afterimage.mc189.state

import gg.sona.afterimage.protocol.DisplayScoreboard
import gg.sona.afterimage.protocol.ScoreboardObjective
import gg.sona.afterimage.protocol.Teams
import gg.sona.afterimage.protocol.UpdateScore
import gg.sona.afterimage.world.ObjectiveState
import gg.sona.afterimage.world.ScoreboardState
import gg.sona.afterimage.world.TeamState


class ShadowScoreboard : ScoreboardState {
    val objectives = LinkedHashMap<String, Objective>()
    val teams = LinkedHashMap<String, Team>()
    val displaySlots = arrayOfNulls<String>(DISPLAY_SLOTS)

    fun apply(packet: ScoreboardObjective) {
        when (packet.mode) {
            ScoreboardObjective.CREATE -> objectives[packet.name] =
                Objective(packet.name, packet.displayName ?: packet.name, packet.type ?: "integer")

            ScoreboardObjective.REMOVE -> {
                objectives.remove(packet.name)
                for (slot in displaySlots.indices) if (displaySlots[slot] == packet.name) displaySlots[slot] = null
            }

            ScoreboardObjective.UPDATE -> objectives[packet.name]?.let {
                it.displayName = packet.displayName ?: it.displayName
                it.type = packet.type ?: it.type
            }
        }
    }

    fun apply(packet: UpdateScore) {
        when (packet.action) {
            UpdateScore.CHANGE -> objectives[packet.objective]?.scores?.put(packet.name, packet.value)
            UpdateScore.REMOVE -> {
                if (packet.objective.isEmpty()) objectives.values.forEach { it.scores.remove(packet.name) }
                else objectives[packet.objective]?.scores?.remove(packet.name)
            }
        }
    }

    fun apply(packet: DisplayScoreboard) {
        if (packet.position in displaySlots.indices) displaySlots[packet.position] = packet.name.ifEmpty { null }
    }

    fun apply(packet: Teams) {
        when (packet.mode) {
            Teams.CREATE -> {
                val team = Team(packet.name)
                update(team, packet)
                for (other in teams.values) other.members.removeAll(packet.players.toSet())
                team.members.addAll(packet.players)
                teams[packet.name] = team
            }

            Teams.REMOVE -> teams.remove(packet.name)
            Teams.UPDATE -> teams[packet.name]?.let { update(it, packet) }
            Teams.ADD_PLAYERS -> teams[packet.name]?.let { team ->
                for (other in teams.values) if (other !== team) other.members.removeAll(packet.players.toSet())
                team.members.addAll(packet.players)
            }

            Teams.REMOVE_PLAYERS -> teams[packet.name]?.members?.removeAll(packet.players.toSet())
        }
    }

    override val allObjectives: Collection<ObjectiveState> get() = objectives.values

    override val allTeams: Collection<TeamState> get() = teams.values

    override fun teamOf(entry: String): Team? = teams.values.firstOrNull { entry in it.members }

    override fun sidebar(): Objective? = displaySlots[DisplayScoreboard.SIDEBAR]?.let { objectives[it] }

    override fun tabList(): Objective? = displaySlots[DisplayScoreboard.LIST]?.let { objectives[it] }

    fun clear() {
        objectives.clear()
        teams.clear()
        displaySlots.fill(null)
    }

    private fun update(team: Team, packet: Teams) {
        team.displayName = packet.displayName ?: team.displayName
        team.prefix = packet.prefix ?: team.prefix
        team.suffix = packet.suffix ?: team.suffix
        team.friendlyFire = packet.friendlyFire
        team.nameTagVisibility = packet.nameTagVisibility ?: team.nameTagVisibility
        team.color = packet.color
    }

    companion object {
        const val DISPLAY_SLOTS = 19
    }
}
