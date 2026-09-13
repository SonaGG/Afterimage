package gg.sona.recast.index.query

import gg.sona.recast.index.IndexEventKind

enum class EventKindSpec(val words: List<String>, val kinds: List<IndexEventKind>) {
    KILL(listOf("kill", "kills"), listOf(IndexEventKind.KILL)),
    DEATH(listOf("death", "deaths", "die", "died"), listOf(IndexEventKind.DEATH)),
    HURT(listOf("hurt", "hit", "hits", "damage"), listOf(IndexEventKind.HURT)),
    ATTACK(listOf("attack", "attacks"), listOf(IndexEventKind.ATTACK)),
    SWING(listOf("swing", "swings"), listOf(IndexEventKind.SWING)),
    CRIT(listOf("crit", "crits", "critical"), listOf(IndexEventKind.CRIT)),
    USE(listOf("use", "uses", "using"), listOf(IndexEventKind.USE_START)),
    EAT(listOf("eat", "eats"), listOf(IndexEventKind.EAT)),
    PICKUP(listOf("pickup", "pickups", "collect"), listOf(IndexEventKind.PICKUP)),
    EQUIP(listOf("equip", "equipment"), listOf(IndexEventKind.EQUIP)),
    EFFECT(listOf("effect", "effects", "potion"), listOf(IndexEventKind.EFFECT)),
    PROJECTILE(
        listOf("projectile", "projectiles", "arrow", "arrows", "shot", "shots"),
        listOf(IndexEventKind.PROJECTILE_SPAWN)
    ),
    IMPACT(listOf("impact", "impacts", "land"), listOf(IndexEventKind.PROJECTILE_END)),
    EXPLOSION(listOf("explosion", "explosions", "explode", "boom"), listOf(IndexEventKind.EXPLOSION)),
    SOUND(listOf("sound", "sounds"), listOf(IndexEventKind.SOUND)),
    CHAT(listOf("chat", "message", "messages"), listOf(IndexEventKind.CHAT)),
    TITLE(listOf("title", "titles"), listOf(IndexEventKind.TITLE)),
    BOSS(listOf("boss", "bossbar"), listOf(IndexEventKind.BOSS)),
    SCOREBOARD(listOf("scoreboard", "sidebar", "score"), listOf(IndexEventKind.SCOREBOARD)),
    ACHIEVEMENT(listOf("achievement", "achievements"), listOf(IndexEventKind.ACHIEVEMENT)),
    RESPAWN(listOf("respawn", "respawns"), listOf(IndexEventKind.RESPAWN)),
    DIMENSION(listOf("dimension", "dimensions", "world"), listOf(IndexEventKind.DIMENSION)),
    JOIN(listOf("join", "joins", "joined"), listOf(IndexEventKind.JOIN)),
    LEAVE(listOf("leave", "leaves", "left", "quit"), listOf(IndexEventKind.LEAVE)),
    MARKER(listOf("marker", "markers"), listOf(IndexEventKind.MARKER)),
    BLOCK(listOf("block", "blocks", "break", "place"), emptyList()),
    EVENT(
        listOf("event", "events", "any"),
        IndexEventKind.entries.filter { it != IndexEventKind.SOUND && it != IndexEventKind.SWING });

    companion object {
        fun of(word: String): EventKindSpec? {
            val text = word.lowercase()
            return entries.firstOrNull { text in it.words }
        }
    }
}
