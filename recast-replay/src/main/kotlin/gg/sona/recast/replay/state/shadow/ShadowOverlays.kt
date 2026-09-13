package gg.sona.recast.replay.state.shadow

import gg.sona.recast.core.time.Nanos
import gg.sona.recast.protocol.ChatMessage
import gg.sona.recast.protocol.OverlayChatLine
import gg.sona.recast.protocol.OverlayReset
import gg.sona.recast.protocol.PlayPacket
import gg.sona.recast.protocol.Title

class ShadowOverlays {
    private val chat = ArrayDeque<ChatLine>()

    var actionBar: ChatLine? = null
        private set

    private var titleJson: String? = null
    private var subtitleJson: String? = null
    private var fadeIn = DEFAULT_FADE_IN
    private var stay = DEFAULT_STAY
    private var fadeOut = DEFAULT_FADE_OUT
    private var titleShownAt = Long.MIN_VALUE

    val title: TitleState get() = TitleState(titleJson, subtitleJson, fadeIn, stay, fadeOut, titleShownAt)

    fun visibleChat(nanos: Long, windowNanos: Long = CHAT_VISIBLE_NANOS): List<ChatLine> =
        chat.filter { it.nanos <= nanos && nanos - it.nanos <= windowNanos }

    fun visibleActionBar(nanos: Long): ChatLine? =
        actionBar?.takeIf { it.nanos <= nanos && nanos - it.nanos <= ACTION_BAR_NANOS }

    fun apply(packet: ChatMessage, nanos: Long) {
        if (packet.position == ACTION_BAR) {
            actionBar = ChatLine(nanos, packet.json)
            return
        }
        chat.addLast(ChatLine(nanos, packet.json))
        while (chat.size > MAX_CHAT) chat.removeFirst()
    }

    fun apply(packet: Title, nanos: Long) {
        when (packet.action) {
            Title.SET_TITLE -> {
                titleJson = packet.textJson ?: EMPTY_TEXT
                titleShownAt = nanos
            }

            Title.SET_SUBTITLE -> subtitleJson = packet.textJson ?: EMPTY_TEXT
            Title.SET_TIMES -> {
                val active = title.activeAt(nanos)
                if (packet.fadeIn >= 0) fadeIn = packet.fadeIn
                if (packet.stay >= 0) stay = packet.stay
                if (packet.fadeOut >= 0) fadeOut = packet.fadeOut
                if (active) titleShownAt = nanos
            }

            Title.HIDE -> {
                titleJson = null
                subtitleJson = null
                titleShownAt = Long.MIN_VALUE
            }

            Title.RESET -> {
                titleJson = null
                subtitleJson = null
                fadeIn = DEFAULT_FADE_IN
                stay = DEFAULT_STAY
                fadeOut = DEFAULT_FADE_OUT
                titleShownAt = Long.MIN_VALUE
            }
        }
    }

    fun apply(packet: OverlayReset, nanos: Long) {
        if (packet.resetsChat) {
            chat.clear()
            for (line in packet.chat.sortedByDescending { it.ageNanos }) chat.addLast(ChatLine(nanos - line.ageNanos, line.json))
        }
        if (packet.resetsTitle) {
            titleShownAt = if (packet.titleAgeNanos < 0 || titleJson == null) Long.MIN_VALUE else nanos - packet.titleAgeNanos
        }
        if (packet.resetsActionBar) {
            val bar = actionBar
            actionBar = if (packet.actionBarAgeNanos < 0 || bar == null) null else ChatLine(nanos - packet.actionBarAgeNanos, bar.json)
        }
    }

    fun snapshot(nanos: Long): List<PlayPacket> {
        val lines = chat.filter { it.nanos <= nanos }
        val bar = visibleActionBar(nanos)
        val current = title
        val titleActive = current.activeAt(nanos)
        val out = ArrayList<PlayPacket>(5)
        bar?.let { out += ChatMessage(it.json, ACTION_BAR) }
        if (titleActive) {
            out += Title(Title.SET_TIMES, null, current.fadeIn, current.stay, current.fadeOut)
            out += Title(Title.SET_SUBTITLE, current.subtitleJson ?: EMPTY_TEXT, 0, 0, 0)
            out += Title(Title.SET_TITLE, current.titleJson, 0, 0, 0)
        }
        out += OverlayReset(
            OverlayReset.CHAT or OverlayReset.TITLE or OverlayReset.ACTION_BAR,
            lines.map { OverlayChatLine(nanos - it.nanos, it.json) },
            if (titleActive) nanos - current.shownAtNanos else OverlayReset.NONE,
            if (bar != null) nanos - bar.nanos else OverlayReset.NONE
        )
        return out
    }

    fun clear() {
        chat.clear()
        actionBar = null
        titleJson = null
        subtitleJson = null
        fadeIn = DEFAULT_FADE_IN
        stay = DEFAULT_STAY
        fadeOut = DEFAULT_FADE_OUT
        titleShownAt = Long.MIN_VALUE
    }

    companion object {
        const val ACTION_BAR = 2
        const val MAX_CHAT = 100
        const val DEFAULT_FADE_IN = 10
        const val DEFAULT_STAY = 70
        const val DEFAULT_FADE_OUT = 20
        const val EMPTY_TEXT = "{\"text\":\"\"}"
        val CHAT_VISIBLE_NANOS: Long = Nanos.ofSeconds(10)
        val ACTION_BAR_NANOS: Long = Nanos.ofSeconds(3)
    }
}
