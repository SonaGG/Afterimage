package gg.sona.recast.replay.state.shadow

import gg.sona.recast.core.time.Nanos
import gg.sona.recast.protocol.ChatMessage
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
                titleJson = packet.textJson
                titleShownAt = nanos
            }

            Title.SET_SUBTITLE -> subtitleJson = packet.textJson
            Title.SET_TIMES -> {
                fadeIn = packet.fadeIn
                stay = packet.stay
                fadeOut = packet.fadeOut
            }

            Title.HIDE -> titleShownAt = Long.MIN_VALUE
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
        val CHAT_VISIBLE_NANOS: Long = Nanos.ofSeconds(10)
        val ACTION_BAR_NANOS: Long = Nanos.ofSeconds(3)
    }
}
