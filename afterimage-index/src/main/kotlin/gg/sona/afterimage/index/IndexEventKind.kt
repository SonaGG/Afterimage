package gg.sona.afterimage.index

enum class IndexEventKind(val label: String, val actorIsB: Boolean = false) {
    KILL("Kill"),
    DEATH("Death", actorIsB = true),
    HURT("Hurt", actorIsB = true),
    ATTACK("Attack"),
    SWING("Swing"),
    CRIT("Critical hit", actorIsB = true),
    USE_START("Item use"),
    USE_END("Item use end"),
    EAT("Eat"),
    PICKUP("Pickup"),
    EQUIP("Equipment"),
    EFFECT("Effect"),
    PROJECTILE_SPAWN("Projectile", actorIsB = true),
    PROJECTILE_END("Projectile end", actorIsB = true),
    EXPLOSION("Explosion", actorIsB = true),
    SOUND("Sound"),
    CHAT("Chat"),
    TITLE("Title"),
    BOSS("Boss bar"),
    SCOREBOARD("Scoreboard"),
    ACHIEVEMENT("Achievement"),
    RESPAWN("Respawn"),
    DIMENSION("Dimension"),
    JOIN("Join"),
    LEAVE("Leave"),
    MARKER("Marker");

    companion object {
        private val byOrdinal = entries.toTypedArray()
        fun of(ordinal: Int): IndexEventKind = byOrdinal[ordinal]
        fun named(name: String): IndexEventKind? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
