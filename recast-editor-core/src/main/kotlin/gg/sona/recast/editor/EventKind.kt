package gg.sona.recast.editor

enum class EventKind(val label: String, val color: Int) {
    KILL("Kill", 0xFF5252),
    DEATH("Death", 0x9E2B2B),
    TITLE("Title", 0xFFD54F),
    RESPAWN("Respawn", 0x80CBC4),
    MARKER("Marker", 0xFFFFFF),
    BOSS("Boss bar", 0xBA68C8),
    CHAT("Chat", 0x90A4AE),
    EXPLOSION("Explosion", 0xFF8A3D),
}
