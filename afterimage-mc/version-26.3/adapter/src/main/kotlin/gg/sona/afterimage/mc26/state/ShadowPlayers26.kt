package gg.sona.afterimage.mc26.state

import gg.sona.afterimage.mc26.mixin.PlayerInfoUpdateAccessor
import gg.sona.afterimage.mc26.mixin.SetPlayerTeamAccessor
import gg.sona.afterimage.mc26.mixin.SetPlayerTeamInvoker
import gg.sona.afterimage.world.ObjectiveState
import gg.sona.afterimage.world.PlayerListState
import gg.sona.afterimage.world.PlayerProfile
import gg.sona.afterimage.world.ScoreboardState
import gg.sona.afterimage.world.TeamState
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundResetScorePacket
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.network.protocol.game.ClientboundTabListPacket
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.criteria.ObjectiveCriteria
import java.util.*

class ShadowPlayer26(override val uuid: UUID, var entry: ClientboundPlayerInfoUpdatePacket.Entry) : PlayerProfile {
    override val name: String get() = entry.profile()?.name ?: uuid.toString().take(16)
    override val gameMode: Int get() = entry.gameMode().id
    override val ping: Int get() = entry.latency()
    override val displayNameJson: String? get() = entry.displayName()?.string
}

class ShadowPlayers26(val known: MutableMap<UUID, ShadowPlayer26> = HashMap()) : PlayerListState {
    val entries = LinkedHashMap<UUID, ShadowPlayer26>()
    var tabList: ClientboundTabListPacket? = null

    override val listed: Collection<PlayerProfile> get() = entries.values

    override fun profile(uuid: UUID): ShadowPlayer26? = entries[uuid] ?: known[uuid]

    override fun knownProfile(uuid: UUID): ShadowPlayer26? = known[uuid]

    fun apply(packet: ClientboundPlayerInfoUpdatePacket) {
        val actions = packet.actions()
        for (update in packet.entries()) {
            val uuid = update.profileId()
            val existing = entries[uuid]
            if (existing == null) {
                if (!actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) continue
                val player = ShadowPlayer26(uuid, update)
                entries[uuid] = player
                known[uuid] = player
                continue
            }
            val current = existing.entry
            existing.entry = ClientboundPlayerInfoUpdatePacket.Entry(
                uuid,
                if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) update.profile() else current.profile(),
                if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED)) update.listed() else current.listed(),
                if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY)) update.latency() else current.latency(),
                if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) update.gameMode() else current.gameMode(),
                if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME)) update.displayName() else current.displayName(),
                if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT)) update.showHat() else current.showHat(),
                if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER)) update.listOrder() else current.listOrder(),
                if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT)) update.chatSession() else current.chatSession(),
            )
        }
    }

    fun apply(packet: ClientboundPlayerInfoRemovePacket) {
        for (uuid in packet.profileIds()) entries.remove(uuid)
    }

    fun clear() {
        entries.clear()
        tabList = null
    }

    fun snapshot(out: MutableList<Packet<*>>) {
        if (entries.isNotEmpty()) out += addPacket(entries.values.map { it.entry })
        tabList?.let { out += it }
    }

    companion object {
        val ALL_ACTIONS: EnumSet<ClientboundPlayerInfoUpdatePacket.Action> = EnumSet.of(
            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER,
        )

        fun addPacket(entries: List<ClientboundPlayerInfoUpdatePacket.Entry>): ClientboundPlayerInfoUpdatePacket {
            val packet = ClientboundPlayerInfoUpdatePacket(EnumSet.copyOf(ALL_ACTIONS), emptyList())
            (packet as PlayerInfoUpdateAccessor).afterimage_setEntries(entries.map { entry ->
                ClientboundPlayerInfoUpdatePacket.Entry(
                    entry.profileId(), entry.profile(), entry.listed(), entry.latency(), entry.gameMode(),
                    entry.displayName(), entry.showHat(), entry.listOrder(), null,
                )
            })
            return packet
        }

        fun updatePacket(action: ClientboundPlayerInfoUpdatePacket.Action, entries: List<ClientboundPlayerInfoUpdatePacket.Entry>): ClientboundPlayerInfoUpdatePacket {
            val packet = ClientboundPlayerInfoUpdatePacket(EnumSet.of(action), emptyList())
            (packet as PlayerInfoUpdateAccessor).afterimage_setEntries(entries)
            return packet
        }
    }
}

class ShadowObjective26(val packet: ClientboundSetObjectivePacket) : ObjectiveState {
    override val name: String get() = packet.objectiveName
    override val displayName: String get() = packet.displayName.string
    override val scores = LinkedHashMap<String, Int>()
    val scorePackets = LinkedHashMap<String, ClientboundSetScorePacket>()
}

class ShadowTeam26(override val name: String) : TeamState {
    var parameters: ClientboundSetPlayerTeamPacket.Parameters? = null
    override val members = LinkedHashSet<String>()
    override val prefix: String get() = parameters?.playerPrefix()?.string ?: ""
    override val suffix: String get() = parameters?.playerSuffix()?.string ?: ""

    fun toPacket(): ClientboundSetPlayerTeamPacket =
        SetPlayerTeamInvoker.afterimage_create(name, METHOD_ADD, Optional.ofNullable(parameters), members.toList())

    companion object {
        const val METHOD_ADD = 0
        const val METHOD_REMOVE = 1
        const val METHOD_CHANGE = 2
        const val METHOD_JOIN = 3
        const val METHOD_LEAVE = 4
    }
}

class ShadowScoreboard26 : ScoreboardState {
    val objectives = LinkedHashMap<String, ShadowObjective26>()
    val teams = LinkedHashMap<String, ShadowTeam26>()
    val display = EnumMap<DisplaySlot, String>(DisplaySlot::class.java)

    override val allObjectives: Collection<ObjectiveState> get() = objectives.values
    override val allTeams: Collection<TeamState> get() = teams.values

    override fun sidebar(): ObjectiveState? = display[DisplaySlot.SIDEBAR]?.let { objectives[it] }

    override fun tabList(): ObjectiveState? = display[DisplaySlot.LIST]?.let { objectives[it] }

    override fun teamOf(entry: String): TeamState? = teams.values.firstOrNull { entry in it.members }

    fun apply(packet: ClientboundSetObjectivePacket) {
        when (packet.method) {
            ClientboundSetObjectivePacket.METHOD_ADD -> objectives[packet.objectiveName] = ShadowObjective26(packet)
            ClientboundSetObjectivePacket.METHOD_REMOVE -> {
                objectives.remove(packet.objectiveName)
                display.entries.removeIf { it.value == packet.objectiveName }
            }

            ClientboundSetObjectivePacket.METHOD_CHANGE -> objectives[packet.objectiveName]?.let { existing ->
                val replacement = ShadowObjective26(packet)
                replacement.scores.putAll(existing.scores)
                replacement.scorePackets.putAll(existing.scorePackets)
                objectives[packet.objectiveName] = replacement
            }
        }
    }

    fun apply(packet: ClientboundSetScorePacket) {
        val objective = objectives[packet.objectiveName()] ?: return
        objective.scores[packet.owner()] = packet.score()
        objective.scorePackets[packet.owner()] = packet
    }

    fun apply(packet: ClientboundResetScorePacket) {
        val name = packet.objectiveName()
        if (name == null) {
            for (objective in objectives.values) {
                objective.scores.remove(packet.owner())
                objective.scorePackets.remove(packet.owner())
            }
        } else objectives[name]?.let {
            it.scores.remove(packet.owner())
            it.scorePackets.remove(packet.owner())
        }
    }

    fun apply(packet: ClientboundSetDisplayObjectivePacket) {
        val name = packet.objectiveName
        if (name == null) display.remove(packet.slot) else display[packet.slot] = name
    }

    fun apply(packet: ClientboundSetPlayerTeamPacket) {
        val method = (packet as SetPlayerTeamAccessor).afterimage_method()
        when (method) {
            ShadowTeam26.METHOD_ADD -> {
                val team = ShadowTeam26(packet.name)
                team.parameters = packet.parameters.orElse(null)
                for (other in teams.values) other.members.removeAll(packet.players.toSet())
                team.members.addAll(packet.players)
                teams[packet.name] = team
            }

            ShadowTeam26.METHOD_REMOVE -> teams.remove(packet.name)
            ShadowTeam26.METHOD_CHANGE -> teams[packet.name]?.let { it.parameters = packet.parameters.orElse(it.parameters) }
            ShadowTeam26.METHOD_JOIN -> teams[packet.name]?.let { team ->
                for (other in teams.values) if (other !== team) other.members.removeAll(packet.players.toSet())
                team.members.addAll(packet.players)
            }

            ShadowTeam26.METHOD_LEAVE -> teams[packet.name]?.members?.removeAll(packet.players.toSet())
        }
    }

    fun clear() {
        objectives.clear()
        teams.clear()
        display.clear()
    }

    fun snapshot(out: MutableList<Packet<*>>) {
        for (objective in objectives.values) {
            out += objectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD)
            out += objective.scorePackets.values
        }
        for ((slot, name) in display) out += ClientboundSetDisplayObjectivePacket(slot, dummyObjective(objectives[name]?.packet ?: continue))
        for (team in teams.values) out += team.toPacket()
    }

    companion object {
        private val SCRATCH = Scoreboard()

        fun dummyObjective(packet: ClientboundSetObjectivePacket): Objective = Objective(
            SCRATCH, packet.objectiveName, ObjectiveCriteria.DUMMY, packet.displayName, packet.renderType, false, packet.numberFormat.orElse(null),
        )

        fun objectivePacket(objective: ShadowObjective26, method: Int): ClientboundSetObjectivePacket =
            ClientboundSetObjectivePacket(dummyObjective(objective.packet), method)
    }
}
