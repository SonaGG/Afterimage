package gg.sona.afterimage.flashback

import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.flashback.rule.*

object Profiles {

    fun anyKill(): GameProfile =
        GameProfile("any-kill", "Any kill", listOf(KillRule(playersOnly = false), MarkerRule()))

    fun genericPvp(): GameProfile = GameProfile(
        "pvp",
        "Generic PvP",
        listOf(
            KillRule(),
            OwnDeathRule(),
            MarkerRule(),
            TitleRule("victory", Regex("(?i)victory|winner|you win"), "Victory", setOf("victory"))
        ),
    )

    fun bedwars(): GameProfile = GameProfile(
        "bedwars",
        "Bedwars",
        listOf(
            KillRule(),
            OwnDeathRule(),
            MarkerRule(),
            ChatRule("final-kill", Regex("FINAL KILL"), "Final kill", setOf("kill", "final-kill")),
            ChatRule("bed-break", Regex("BED DESTRUCTION"), "Bed break", setOf("bed-break")),
            ScoreboardRule("beds-destroyed", Regex("(?i)beds? destroyed"), "Bed destroyed", setOf("bed-break")),
            TitleRule("victory", Regex("(?i)victory"), "Victory", setOf("victory")),
            TitleRule("game-over", Regex("(?i)game over"), "Game over", setOf("game-over")),
        ),
        preRollNanos = Nanos.ofSeconds(12),
        postRollNanos = Nanos.ofSeconds(4),
    )

    fun all(): List<GameProfile> = listOf(bedwars(), genericPvp(), anyKill())
}
